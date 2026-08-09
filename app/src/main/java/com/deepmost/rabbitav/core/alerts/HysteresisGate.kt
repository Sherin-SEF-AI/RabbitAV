package com.deepmost.rabbitav.core.alerts

/**
 * The per-(track, kind) alert state machine (Section 5.5): a desired level
 * must hold [holdToFireNs] to fire; must stay lower [holdToClearNs] to
 * downgrade; re-firing the SAME level within the cooldown is suppressed but
 * escalation to a HIGHER level always passes (safety: cooldown must never mute
 * a CRITICAL).
 */
class HysteresisGate(
    private val holdToFireNs: Long,
    private val holdToClearNs: Long,
    private val cooldownNs: Long,
) {
    var current: AlertLevel = AlertLevel.NONE
        private set

    private var pendingLevel: AlertLevel = AlertLevel.NONE
    private var pendingSinceNs = 0L
    private var lowerSinceNs = 0L
    private val cooldownUntil = LongArray(AlertLevel.entries.size)

    var lastChangeNs = 0L
        private set

    fun update(desired: AlertLevel, tNs: Long): AlertLevel {
        when {
            desired.ordinal > current.ordinal -> {
                lowerSinceNs = 0L
                val gated = if (tNs < cooldownUntil[desired.ordinal]) current else desired
                if (gated.ordinal > current.ordinal) {
                    if (pendingLevel != gated) {
                        pendingLevel = gated
                        pendingSinceNs = tNs
                    }
                    if (tNs - pendingSinceNs >= holdToFireNs) {
                        current = gated
                        lastChangeNs = tNs
                        pendingLevel = AlertLevel.NONE
                    }
                } else {
                    pendingLevel = AlertLevel.NONE
                }
            }
            desired.ordinal < current.ordinal -> {
                pendingLevel = AlertLevel.NONE
                if (lowerSinceNs == 0L) lowerSinceNs = tNs
                if (tNs - lowerSinceNs >= holdToClearNs) {
                    // leaving `current` starts its re-fire cooldown
                    cooldownUntil[current.ordinal] = tNs + cooldownNs
                    current = desired
                    lastChangeNs = tNs
                    lowerSinceNs = 0L
                }
            }
            else -> {
                pendingLevel = AlertLevel.NONE
                lowerSinceNs = 0L
            }
        }
        return current
    }

    /** True when nothing is active or pending — the gate can be pruned. */
    val isIdle: Boolean get() = current == AlertLevel.NONE && pendingLevel == AlertLevel.NONE
}
