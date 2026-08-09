package com.deepmost.rabbitav.core.imu

import kotlin.math.abs
import kotlin.math.sqrt
import timber.log.Timber

/**
 * The Section 5.6 jolt engine, pure Kotlin for unit-test replay: consumes the
 * band-passed vertical channel sample by sample, triggers on
 * |az| > max(2.5, 6*MAD), captures a 1.5 s window centered on the peak,
 * extracts features, and classifies deterministically.
 *
 * A parallel path watches for ROUGH_PATCH: sustained elevated RMS (>2 s)
 * without a dominant single peak.
 */
class JoltDetector(
    private val fsHz: Float,
    private val hasGyro: Boolean,
    private val onCandidate: (HazardCandidate) -> Unit,
) {
    private val bandPass = BandPass(fsHz)
    private val mad = RollingMad()

    // 3 s history ring of the band-passed channel (for the pre-peak half window)
    private val histCap = (fsHz * 3f).toInt().coerceAtLeast(64)
    private val histV = FloatArray(histCap)
    private val histT = LongArray(histCap)
    private val histPitch = FloatArray(histCap)
    private var histIdx = 0
    private var histFilled = 0

    // capture state machine
    private enum class State { IDLE, CAPTURING, REFRACTORY }
    private var state = State.IDLE
    private var triggerNs = 0L
    private var refractoryUntilNs = 0L

    // rough patch state
    private var rmsAcc = 0f
    private var rmsCount = 0
    private var rmsWindowStartNs = 0L
    private var shortRms = 0f
    private var elevatedSinceNs = 0L
    private var roughPeaked = false
    private var roughRefractoryUntilNs = 0L

    /** MAD baseline captured while quiet and FROZEN during an elevated span —
     *  otherwise a sustained rough patch raises its own detection bar and the
     *  2 s condition can never be met. */
    private var roughBaselineMad = 0f

    /** Ego speed provider (m/s); gate: no triggers below 8 km/h. */
    var egoSpeedMps: () -> Float = { 0f }

    /** Adaptation freeze hook (VehicleFrameAligner.freezeAdaptation). */
    var onCaptureStateChanged: (capturing: Boolean) -> Unit = {}

    /** Debug trace of the latest band-passed value. */
    @Volatile var lastBandPassed = 0f
        private set
    @Volatile var lastThreshold = TRIGGER_FLOOR_MPS2
        private set

    /**
     * @param verticalRaw vertical specific-force deviation BEFORE band-pass
     * @param pitchRate rad/s (NaN when unavailable)
     * @param tNs monotonic timestamp
     */
    fun process(verticalRaw: Float, pitchRate: Float, tNs: Long) {
        val v = bandPass.process(verticalRaw)
        lastBandPassed = v
        val absV = abs(v)
        mad.push(absV, tNs)

        histV[histIdx] = v
        histT[histIdx] = tNs
        histPitch[histIdx] = pitchRate
        histIdx = (histIdx + 1) % histCap
        if (histFilled < histCap) histFilled++

        val speed = egoSpeedMps()
        val threshold = maxOf(TRIGGER_FLOOR_MPS2, TRIGGER_MAD_MULTIPLIER * mad.mad(tNs))
        lastThreshold = threshold

        when (state) {
            State.IDLE -> {
                if (speed >= MIN_SPEED_MPS && absV > threshold) {
                    state = State.CAPTURING
                    triggerNs = tNs
                    onCaptureStateChanged(true)
                }
            }
            State.CAPTURING -> {
                // collect until 0.9 s past trigger, then assemble the window
                if (tNs - triggerNs >= (0.9e9f).toLong()) {
                    assembleAndClassify(tNs, speed)
                    state = State.REFRACTORY
                    refractoryUntilNs = tNs + (0.5e9f).toLong()
                    onCaptureStateChanged(false)
                }
            }
            State.REFRACTORY -> {
                if (tNs >= refractoryUntilNs) state = State.IDLE
            }
        }

        roughPatchTick(v, absV, threshold, speed, tNs)
    }

    // ------------------------------------------------------------------ jolt

    private fun assembleAndClassify(nowNs: Long, speedMps: Float) {
        // locate the absolute peak in [trigger - 0.1 s, trigger + 0.9 s]
        val from = triggerNs - (0.1e9f).toLong()
        var peakT = triggerNs
        var peakAbs = 0f
        forEachHist { t, v, _ ->
            if (t in from..nowNs && abs(v) > peakAbs) {
                peakAbs = abs(v)
                peakT = t
            }
        }
        val winFrom = peakT - (WINDOW_HALF_S * 1e9f).toLong()
        val winTo = peakT + (WINDOW_HALF_S * 1e9f).toLong()

        // copy the window out (event path; allocation fine)
        val vs = ArrayList<Float>(256)
        val ts = ArrayList<Long>(256)
        val ps = ArrayList<Float>(256)
        forEachHist { t, v, p ->
            if (t in winFrom..winTo) {
                ts.add(t); vs.add(v); ps.add(p)
            }
        }
        if (vs.size < 8) return

        val features = extractFeatures(vs, ts, ps)
        val (type, conf) = classify(features)
        val capped = if (hasGyro) conf else minOf(conf, NO_GYRO_CONF_CAP)
        Timber.tag(TAG).i(
            "jolt: %s conf=%.2f peak+%.1f/-%.1f dur=%.2fs dbl=%b speed=%.1fkmh",
            type, capped, features.peakPositive, features.peakNegative,
            features.durationAboveHalfPeakS, features.doubleBump, speedMps * 3.6f
        )
        onCandidate(
            HazardCandidate(type, capped, peakT, features, speedMps)
        )
    }

    private inline fun forEachHist(block: (t: Long, v: Float, pitch: Float) -> Unit) {
        val n = histFilled
        val start = (histIdx - n + histCap) % histCap
        for (i in 0 until n) {
            val idx = (start + i) % histCap
            block(histT[idx], histV[idx], histPitch[idx])
        }
    }

    private fun extractFeatures(vs: List<Float>, ts: List<Long>, ps: List<Float>): JoltFeatures {
        var peakPos = 0f
        var peakNeg = 0f
        var peakPosIdx = 0
        var peakNegIdx = 0
        for (i in vs.indices) {
            val v = vs[i]
            if (v > peakPos) { peakPos = v; peakPosIdx = i }
            if (-v > peakNeg) { peakNeg = -v; peakNegIdx = i }
        }
        val maxAbs = maxOf(peakPos, peakNeg)
        val half = maxAbs / 2f

        // which extreme happens first: earliest sample exceeding 60% of its peak
        var firstPosIdx = Int.MAX_VALUE
        var firstNegIdx = Int.MAX_VALUE
        for (i in vs.indices) {
            if (firstPosIdx == Int.MAX_VALUE && peakPos > 0.5f && vs[i] > 0.6f * peakPos) firstPosIdx = i
            if (firstNegIdx == Int.MAX_VALUE && peakNeg > 0.5f && -vs[i] > 0.6f * peakNeg) firstNegIdx = i
        }
        val negativeFirst = firstNegIdx < firstPosIdx

        // contiguous span above half-peak around the dominant extreme
        val domIdx = if (peakPos >= peakNeg) peakPosIdx else peakNegIdx
        var lo = domIdx
        var hi = domIdx
        while (lo > 0 && abs(vs[lo - 1]) > half) lo--
        while (hi < vs.size - 1 && abs(vs[hi + 1]) > half) hi++
        val durationAboveHalf = (ts[hi] - ts[lo]) / 1e9f

        // energy + rms
        var energy = 0f
        var sumSq = 0f
        val dt = 1f / fsHz
        for (v in vs) {
            energy += v * v * dt
            sumSq += v * v
        }
        val rms = sqrt(sumSq / vs.size)

        // double bump: two positive local maxima, comparable height, 0.15-0.9 s apart
        var doubleBump = false
        var symmetric = false
        var gapS = 0f
        if (peakPos > 1f) {
            var best2 = 0f
            var best2Idx = -1
            for (i in 1 until vs.size - 1) {
                if (vs[i] > vs[i - 1] && vs[i] >= vs[i + 1] && i != peakPosIdx) {
                    val gap = abs(ts[i] - ts[peakPosIdx]) / 1e9f
                    if (gap in DOUBLE_BUMP_MIN_GAP_S..DOUBLE_BUMP_MAX_GAP_S && vs[i] > best2) {
                        best2 = vs[i]
                        best2Idx = i
                    }
                }
            }
            if (best2Idx >= 0 && best2 > 0.5f * peakPos) {
                doubleBump = true
                gapS = abs(ts[best2Idx] - ts[peakPosIdx]) / 1e9f
                val ratio = best2 / peakPos
                symmetric = ratio in 0.5f..2.0f
            }
        }

        // Count contiguous regions above 60% of the positive peak — the
        // breaker-vs-washboard structural discriminator. A breaker's two axle
        // humps give exactly 2 regions (crest noise cannot split a hump, the
        // bar sits far below the crest); washboard noise crosses the bar many
        // times. Regions closer than 80 ms merge (one hump, brief dip).
        var peakCount = 0
        if (peakPos > 0.5f) {
            val bar = 0.6f * peakPos
            var inRegion = false
            // sentinel far in the past but safe from Long-subtraction overflow
            var lastRegionEndT = Long.MIN_VALUE / 4
            for (i in vs.indices) {
                if (vs[i] > bar) {
                    if (!inRegion) {
                        inRegion = true
                        if (ts[i] - lastRegionEndT > 80_000_000L) peakCount++
                    }
                    lastRegionEndT = ts[i]
                } else {
                    inRegion = false
                }
            }
        }

        var pitchRange = Float.NaN
        if (hasGyro) {
            var mn = Float.MAX_VALUE
            var mx = -Float.MAX_VALUE
            var any = false
            for (p in ps) {
                if (p.isNaN()) continue
                any = true
                if (p < mn) mn = p
                if (p > mx) mx = p
            }
            if (any) pitchRange = mx - mn
        }

        return JoltFeatures(
            peakPositive = peakPos,
            peakNegative = peakNeg,
            negativeFirst = negativeFirst,
            durationAboveHalfPeakS = durationAboveHalf,
            energy = energy,
            doubleBump = doubleBump,
            doubleBumpSymmetric = symmetric,
            doubleBumpGapS = gapS,
            positivePeakCount = peakCount,
            windowRms = rms,
            gyroPitchRateRange = pitchRange,
        )
    }

    /** Rule-based classifier v1 (Section 5.6). Deterministic; unit-tested.
     *  Both discrete classes require the peak to DOMINATE the window RMS —
     *  broadband washboard vibration must not mint potholes/breakers. */
    fun classify(f: JoltFeatures): Pair<HazardType, Float> {
        // SPEED_BREAKER: positive-first lift + symmetric double bump (both
        // axles) with EXACTLY the two-to-three coherent peaks a breaker makes;
        // washboard vibration shows many comparable peaks and is rejected.
        if (!f.negativeFirst && f.doubleBump && f.doubleBumpSymmetric &&
            f.positivePeakCount in 2..3
        ) {
            var conf = 0.75f
            if (!f.gyroPitchRateRange.isNaN() && f.gyroPitchRateRange > 0.15f) conf += 0.1f
            return HazardType.SPEED_BREAKER to conf.coerceAtMost(0.85f)
        }
        // POTHOLE: sharp negative-first drop, short event, dominant peak
        if (f.negativeFirst && f.durationAboveHalfPeakS < POTHOLE_MAX_DURATION_S &&
            f.peakNegative > 2f && f.peakNegative > POTHOLE_DOMINANCE * f.windowRms
        ) {
            var conf = 0.7f
            if (f.peakPositive > 0.6f * f.peakNegative) conf += 0.1f // hard rebound off the far edge
            return HazardType.POTHOLE to conf.coerceAtMost(0.8f)
        }
        return HazardType.UNKNOWN to 0.4f
    }

    // ----------------------------------------------------------- rough patch

    private fun roughPatchTick(v: Float, absV: Float, threshold: Float, speed: Float, tNs: Long) {
        // 0.5 s short-window RMS
        rmsAcc += v * v
        rmsCount++
        if (rmsWindowStartNs == 0L) rmsWindowStartNs = tNs
        if (tNs - rmsWindowStartNs >= (0.5e9f).toLong() && rmsCount > 0) {
            shortRms = sqrt(rmsAcc / rmsCount)
            rmsAcc = 0f
            rmsCount = 0
            rmsWindowStartNs = tNs

            if (elevatedSinceNs == 0L) roughBaselineMad = mad.mad(tNs)
            val enterBar = maxOf(ROUGH_RMS_FLOOR_MPS2, 3f * roughBaselineMad)
            // Schmitt trigger: once elevated, only a clearly lower RMS ends the
            // span — 0.5 s RMS windows on real washboard roads vary +-15% and
            // must not fragment one patch into many sub-2 s spans.
            val elevated = if (elevatedSinceNs == 0L) shortRms > enterBar else shortRms > enterBar * ROUGH_EXIT_FRACTION
            if (elevated && speed >= MIN_SPEED_MPS && tNs >= roughRefractoryUntilNs) {
                if (elevatedSinceNs == 0L) {
                    elevatedSinceNs = tNs
                    roughPeaked = false
                }
            } else if (!elevated) {
                // window closed: emit if it lasted and never became a single-peak jolt
                if (elevatedSinceNs != 0L && tNs - elevatedSinceNs >= (ROUGH_MIN_DURATION_S * 1e9f).toLong() && !roughPeaked) {
                    Timber.tag(TAG).i("rough patch: rms=%.2f dur=%.1fs", shortRms, (tNs - elevatedSinceNs) / 1e9f)
                    onCandidate(
                        HazardCandidate(
                            HazardType.ROUGH_PATCH,
                            if (hasGyro) 0.6f else minOf(0.6f, NO_GYRO_CONF_CAP),
                            tNs,
                            null,
                            speed,
                        )
                    )
                    roughRefractoryUntilNs = tNs + (10e9f).toLong()
                }
                elevatedSinceNs = 0L
            }
        }
        // A DOMINANT single peak during the elevated span reclassifies the
        // stretch as a jolt event: dominant means well above both the adaptive
        // trigger and the ambient RMS (4x RMS keeps ordinary vibration tails
        // from vetoing a genuine rough patch).
        if (elevatedSinceNs != 0L && absV > maxOf(threshold * 1.5f, shortRms * 4f)) roughPeaked = true
    }

    fun reset() {
        bandPass.reset()
        mad.reset()
        histFilled = 0
        histIdx = 0
        state = State.IDLE
        elevatedSinceNs = 0L
    }

    companion object {
        private const val TAG = "RAV-IMU"

        /** Absolute trigger floor (Section 5.6): 2.5 m/s^2. */
        const val TRIGGER_FLOOR_MPS2 = 2.5f

        /** Adaptive trigger: 6 x rolling MAD (Section 5.6). */
        const val TRIGGER_MAD_MULTIPLIER = 6f

        /** Speed gate (Section 5.6): 8 km/h. */
        const val MIN_SPEED_MPS = 8f / 3.6f

        /** Event window half-width: 1.5 s window centered on the peak. */
        const val WINDOW_HALF_S = 0.75f

        /** Pothole events are over in under this (Section 5.6): 0.35 s. */
        const val POTHOLE_MAX_DURATION_S = 0.35f

        /** Double-bump axle gap window (Section 5.6): 0.15-0.9 s. */
        const val DOUBLE_BUMP_MIN_GAP_S = 0.15f
        const val DOUBLE_BUMP_MAX_GAP_S = 0.9f

        /** Rough patch: sustained RMS floor and minimum duration. */
        const val ROUGH_RMS_FLOOR_MPS2 = 1.2f
        const val ROUGH_MIN_DURATION_S = 2.0f

        /** Schmitt-trigger exit: span ends only below this fraction of the
         *  entry bar. Range 0.6-0.9. */
        const val ROUGH_EXIT_FRACTION = 0.75f

        /** Pothole peak must exceed this multiple of the window RMS.
         *  Range 1.5-3.5; higher = fewer false potholes on washboard roads. */
        const val POTHOLE_DOMINANCE = 2.5f

        /** Confidence cap without a gyroscope (Section 5.6). */
        const val NO_GYRO_CONF_CAP = 0.7f
    }
}
