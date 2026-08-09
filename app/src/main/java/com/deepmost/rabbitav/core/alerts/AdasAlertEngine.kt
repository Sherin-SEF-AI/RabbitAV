package com.deepmost.rabbitav.core.alerts

import com.deepmost.rabbitav.core.ego.EgoState
import com.deepmost.rabbitav.core.inference.CanonicalClass
import com.deepmost.rabbitav.core.tracking.TrackSnapshot

/**
 * Evaluates the Section 5.5 alert table each 25 Hz tick and returns the
 * complete current alert set. Pure logic — no Android dependencies — so the
 * M2 gate can drive it deterministically from replay/unit tests.
 */
class AdasAlertEngine(
    private val tuningProvider: () -> AlertTuning,
) {
    private class GateKey(var trackId: Int = 0, var kind: AlertKind = AlertKind.FCW) {
        override fun hashCode(): Int = trackId * 31 + kind.ordinal
        override fun equals(other: Any?): Boolean =
            other is GateKey && other.trackId == trackId && other.kind == kind
    }

    private val gates = HashMap<GateKey, HysteresisGate>()
    private val lookupKey = GateKey()
    private var lastPruneNs = 0L

    /** Set by the pipeline from the loaded model's sidecar capabilities. */
    var visualHazardCapability: Boolean = false

    /** Settings flag (stretch feature, default off). */
    var wrongSideEnabled: Boolean = false

    fun reset() = gates.clear()

    fun evaluate(
        tracks: List<TrackSnapshot>,
        ego: EgoState,
        calibrationValid: Boolean,
        tNs: Long,
        out: MutableList<ActiveAlert>,
    ) {
        val tuning = tuningProvider()
        out.clear()

        // Global gates (Section 5.5): calibration present, ego speed known and
        // above the floor. Below them, gates decay naturally via NONE updates.
        val egoKmh = if (ego.speedValid) ego.speedKmh else Float.NaN
        val adasEnabled = calibrationValid && ego.speedValid && egoKmh >= tuning.globalMinSpeedKmh

        for (t in tracks) {
            evalFcw(t, egoKmh, adasEnabled, tuning, tNs, out)
            evalHeadway(t, egoKmh, adasEnabled, tuning, tNs, out)
            evalVru(t, ego, egoKmh, adasEnabled, tuning, tNs, out)
            evalVisualHazard(t, ego, egoKmh, adasEnabled, tuning, tNs, out)
            evalWrongSide(t, ego, egoKmh, adasEnabled, tuning, tNs, out)
        }

        if (tNs - lastPruneNs > PRUNE_PERIOD_NS) {
            lastPruneNs = tNs
            pruneGates(tracks)
        }
    }

    private fun gate(trackId: Int, kind: AlertKind, tuning: AlertTuning, fireHoldOverrideS: Float = -1f): HysteresisGate {
        lookupKey.trackId = trackId
        lookupKey.kind = kind
        return gates.getOrPut(GateKey(trackId, kind)) {
            HysteresisGate(
                holdToFireNs = ((if (fireHoldOverrideS > 0f) fireHoldOverrideS else tuning.holdToFireS) * 1e9f).toLong(),
                holdToClearNs = (tuning.holdToClearS * 1e9f).toLong(),
                cooldownNs = (tuning.perTrackCooldownS * 1e9f).toLong(),
            )
        }
    }

    private fun evalFcw(
        t: TrackSnapshot, egoKmh: Float, adasEnabled: Boolean,
        tuning: AlertTuning, tNs: Long, out: MutableList<ActiveAlert>,
    ) {
        if (!t.canonical.isObstacle) return
        val speedGate = if (t.canonical.isVru) tuning.fcwMinSpeedVruKmh else tuning.fcwMinSpeedKmh
        val preconditions = adasEnabled &&
            t.confirmed && t.inCorridor &&
            egoKmh >= speedGate &&
            t.closingMps > tuning.fcwMinClosingMps &&
            !t.zMeters.isNaN()

        val desired = when {
            !preconditions -> AlertLevel.NONE
            t.ttcS <= tuning.fcwTtcCriticalS -> AlertLevel.CRITICAL
            t.ttcS <= tuning.fcwTtcCautionS -> AlertLevel.CAUTION
            else -> AlertLevel.NONE
        }
        val fired = gate(t.id, AlertKind.FCW, tuning).update(desired, tNs)
        when (fired) {
            AlertLevel.CRITICAL -> out.add(
                ActiveAlert(
                    AlertKind.FCW, fired, Tone.FCW_CRITICAL, t.id, t.zMeters, t.ttcS,
                    hudTextKey = "alert_fcw_critical"
                )
            )
            AlertLevel.CAUTION -> out.add(
                ActiveAlert(
                    AlertKind.FCW, fired, Tone.FCW_CAUTION, t.id, t.zMeters, t.ttcS,
                    hudTextKey = "alert_fcw_caution"
                )
            )
            else -> Unit
        }
    }

    private fun evalHeadway(
        t: TrackSnapshot, egoKmh: Float, adasEnabled: Boolean,
        tuning: AlertTuning, tNs: Long, out: MutableList<ActiveAlert>,
    ) {
        if (!t.canonical.isVehicle) return
        val preconditions = adasEnabled &&
            t.confirmed && t.inCorridor &&
            t.corridorForS >= tuning.headwayStableS &&
            egoKmh >= tuning.headwayMinSpeedKmh &&
            t.headwayS.isFinite()

        val desired = when {
            !preconditions -> AlertLevel.NONE
            t.headwayS < tuning.headwayWarningS -> AlertLevel.WARNING
            t.headwayS < tuning.headwayAdvisoryS -> AlertLevel.ADVISORY
            else -> AlertLevel.NONE
        }
        val fired = gate(t.id, AlertKind.HEADWAY, tuning).update(desired, tNs)
        when (fired) {
            AlertLevel.WARNING -> out.add(
                ActiveAlert(
                    AlertKind.HEADWAY, fired, Tone.HEADWAY_WARNING, t.id, t.zMeters, t.headwayS,
                    hudTextKey = "alert_headway_warning"
                )
            )
            AlertLevel.ADVISORY -> out.add(
                ActiveAlert(
                    AlertKind.HEADWAY, fired, Tone.HEADWAY_ADVISORY, t.id, t.zMeters, t.headwayS,
                    hudTextKey = "alert_headway_advisory"
                )
            )
            else -> Unit
        }
    }

    private fun evalVru(
        t: TrackSnapshot, ego: EgoState, egoKmh: Float, adasEnabled: Boolean,
        tuning: AlertTuning, tNs: Long, out: MutableList<ActiveAlert>,
    ) {
        if (!t.canonical.isVru) return
        val radius = maxOf(tuning.vruMinRadiusM, ego.speedMps * tuning.vruTimeRadiusS)
        val preconditions = adasEnabled && t.confirmed && t.inCorridor &&
            !t.zMeters.isNaN() && t.zMeters < radius
        val desired = if (preconditions) AlertLevel.WARNING else AlertLevel.NONE
        val fired = gate(t.id, AlertKind.VRU, tuning).update(desired, tNs)
        if (fired == AlertLevel.WARNING) {
            out.add(
                ActiveAlert(
                    AlertKind.VRU, fired, Tone.VRU, t.id, t.zMeters,
                    hudTextKey = if (t.canonical == CanonicalClass.ANIMAL) "alert_animal" else "alert_vru"
                )
            )
        }
    }

    private fun evalVisualHazard(
        t: TrackSnapshot, ego: EgoState, egoKmh: Float, adasEnabled: Boolean,
        tuning: AlertTuning, tNs: Long, out: MutableList<ActiveAlert>,
    ) {
        if (!visualHazardCapability || !t.canonical.isRoadHazard) return
        val preconditions = adasEnabled && t.confirmed && t.inCorridor &&
            !t.zMeters.isNaN() && t.zMeters < ego.speedMps * tuning.hazardVisualTimeS
        val desired = if (preconditions) AlertLevel.ADVISORY else AlertLevel.NONE
        val fired = gate(t.id, AlertKind.HAZARD_VISUAL, tuning).update(desired, tNs)
        if (fired == AlertLevel.ADVISORY) {
            out.add(
                ActiveAlert(
                    AlertKind.HAZARD_VISUAL, fired, Tone.HAZARD_VISUAL, t.id, t.zMeters,
                    hudTextKey = when (t.canonical) {
                        CanonicalClass.SPEED_BREAKER -> "alert_breaker_visual"
                        else -> "alert_pothole_visual"
                    }
                )
            )
        }
    }

    private fun evalWrongSide(
        t: TrackSnapshot, ego: EgoState, egoKmh: Float, adasEnabled: Boolean,
        tuning: AlertTuning, tNs: Long, out: MutableList<ActiveAlert>,
    ) {
        if (!wrongSideEnabled || !t.canonical.isVehicle) return
        val preconditions = adasEnabled && t.confirmed && t.inCorridor &&
            t.closingMps > ego.speedMps + tuning.wrongSideClosingMarginMps
        val desired = if (preconditions) AlertLevel.CRITICAL else AlertLevel.NONE
        // sustain requirement implemented as a longer fire-hold
        val fired = gate(t.id, AlertKind.WRONG_SIDE, tuning, fireHoldOverrideS = tuning.wrongSideSustainS)
            .update(desired, tNs)
        if (fired == AlertLevel.CRITICAL) {
            out.add(
                ActiveAlert(
                    AlertKind.WRONG_SIDE, fired, Tone.WRONG_SIDE, t.id, t.zMeters, t.ttcS,
                    hudTextKey = "alert_wrong_side"
                )
            )
        }
    }

    private fun pruneGates(tracks: List<TrackSnapshot>) {
        if (gates.size < 64) return
        val liveIds = HashSet<Int>(tracks.size)
        for (t in tracks) liveIds.add(t.id)
        gates.entries.removeAll { (k, g) -> k.trackId !in liveIds && g.isIdle }
    }

    companion object {
        private const val PRUNE_PERIOD_NS = 5_000_000_000L
    }
}
