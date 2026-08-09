package com.deepmost.rabbitav.core.governor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Section 5.10 adaptive controller. Levels:
 *  L0 full (configured input size, preview on)
 *  L1 inference capped 8 FPS
 *  L2 input 256x256, preview off, HUD minimal
 *  L3 detector paused, ADAS suspended (HUD notice + one-time tone);
 *     IMU hazard mapping and GPS NEVER degrade.
 *
 * Promotion: one level per 60 s of sustained pressure. Demotion: after 120 s
 * of headroom. Every transition is logged.
 */
class PerfGovernor(
    private val thermal: ThermalMonitor,
) {
    enum class Level { L0, L1, L2, L3 }

    data class GovernorState(
        val level: Level = Level.L0,
        /** 0 = uncapped. */
        val inferenceCapFps: Int = 0,
        val inputSize: Int = 0, // 0 = model default
        val previewEnabled: Boolean = true,
        val detectorEnabled: Boolean = true,
        val reason: String = "",
    )

    private val _state = MutableStateFlow(GovernorState())
    val state: StateFlow<GovernorState> = _state

    private var pressureSinceMs = 0L
    private var headroomSinceMs = 0L

    /** Inference p90 over the last 10 s, supplied by the perf monitor. */
    @Volatile var inferenceP90Ms: Float = 0f

    /** Ratio of analyzer frames dropped (busy executor) over the last 10 s. */
    @Volatile var frameDropRatio: Float = 0f

    /** Floor-device p90 that counts as compute pressure at L0/L1. */
    private fun computePressure(): Boolean = inferenceP90Ms > 140f || frameDropRatio > 0.85f

    /** Called every ~1 s by the pipeline. */
    fun tick(nowMs: Long) {
        val thermalPressure = thermal.pressure()
        val pressured = thermalPressure >= PRESSURE_THRESHOLD ||
            (_state.value.level != Level.L3 && computePressure() && thermalPressure >= 0.7f)
        val cool = thermalPressure <= HEADROOM_THRESHOLD

        if (pressured) {
            headroomSinceMs = 0L
            if (pressureSinceMs == 0L) pressureSinceMs = nowMs
            if (nowMs - pressureSinceMs >= PROMOTE_AFTER_MS) {
                pressureSinceMs = 0L
                promote(thermalPressure)
            }
        } else if (cool) {
            pressureSinceMs = 0L
            if (headroomSinceMs == 0L) headroomSinceMs = nowMs
            if (nowMs - headroomSinceMs >= DEMOTE_AFTER_MS) {
                headroomSinceMs = 0L
                demote(thermalPressure)
            }
        } else {
            pressureSinceMs = 0L
            headroomSinceMs = 0L
        }
    }

    private fun promote(pressure: Float) {
        val next = when (_state.value.level) {
            Level.L0 -> stateFor(Level.L1, pressure)
            Level.L1 -> stateFor(Level.L2, pressure)
            Level.L2 -> stateFor(Level.L3, pressure)
            Level.L3 -> return
        }
        Timber.tag(TAG).w(
            "governor PROMOTE %s -> %s (thermal=%.2f p90=%.0fms drop=%.2f)",
            _state.value.level, next.level, pressure, inferenceP90Ms, frameDropRatio
        )
        _state.value = next
    }

    private fun demote(pressure: Float) {
        val next = when (_state.value.level) {
            Level.L0 -> return
            Level.L1 -> stateFor(Level.L0, pressure)
            Level.L2 -> stateFor(Level.L1, pressure)
            Level.L3 -> stateFor(Level.L2, pressure)
        }
        Timber.tag(TAG).i("governor demote %s -> %s (thermal=%.2f)", _state.value.level, next.level, pressure)
        _state.value = next
    }

    private fun stateFor(level: Level, pressure: Float): GovernorState = when (level) {
        Level.L0 -> GovernorState(Level.L0, 0, 0, previewEnabled = true, detectorEnabled = true, reason = "full")
        Level.L1 -> GovernorState(Level.L1, 8, 0, previewEnabled = true, detectorEnabled = true, reason = "thermal %.2f".format(pressure))
        Level.L2 -> GovernorState(Level.L2, 8, 256, previewEnabled = false, detectorEnabled = true, reason = "thermal %.2f".format(pressure))
        Level.L3 -> GovernorState(Level.L3, 0, 0, previewEnabled = false, detectorEnabled = false, reason = "thermal %.2f".format(pressure))
    }

    fun reset() {
        _state.value = GovernorState()
        pressureSinceMs = 0L
        headroomSinceMs = 0L
    }

    companion object {
        private const val TAG = "RAV-Gov"

        /** Headroom above this counts as pressure. */
        const val PRESSURE_THRESHOLD = 0.95f

        /** Headroom below this counts as recovery. */
        const val HEADROOM_THRESHOLD = 0.80f

        /** Promote one level per 60 s of sustained pressure (Section 5.10). */
        const val PROMOTE_AFTER_MS = 60_000L

        /** Demote after 120 s of headroom (Section 5.10). */
        const val DEMOTE_AFTER_MS = 120_000L
    }
}
