package com.deepmost.rabbitav.core.alerts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Global arbiter (Section 5.5): merges per-tick alert sets from the ADAS
 * engine and the mapped-hazard approach monitor, keeps a single audio channel
 * with strict priority, and exposes the top alert to the HUD.
 */
class AlertArbiter(
    private val audio: AudioEngine,
    private val onAlertOnset: (ActiveAlert) -> Unit,
) {
    private val _topAlert = MutableStateFlow<ActiveAlert?>(null)
    val topAlert: StateFlow<ActiveAlert?> = _topAlert

    private val _allAlerts = MutableStateFlow<List<ActiveAlert>>(emptyList())
    val allAlerts: StateFlow<List<ActiveAlert>> = _allAlerts

    private var soundingIdentity = 0L
    private var lastAdvisoryToneMs = 0L

    /** Advisory chime rate limit (Section 5.5: max once per 10 s). */
    var advisoryCooldownMs: Long = 10_000

    private val seenIdentities = HashSet<Long>()

    fun submit(alerts: List<ActiveAlert>) {
        _allAlerts.value = alerts.toList()
        val top = alerts.maxByOrNull { it.priority }
        _topAlert.value = top

        // onset bookkeeping (fires the persistence hook exactly once per onset)
        val currentIds = HashSet<Long>(alerts.size)
        for (a in alerts) {
            currentIds.add(a.identity)
            if (a.identity !in seenIdentities) {
                onAlertOnset(a)
                Timber.tag(TAG).i(
                    "ALERT %s/%s track=%d z=%.1fm t=%.2fs", a.kind, a.level, a.trackId, a.distanceM, a.secondsToEvent
                )
            }
        }
        seenIdentities.retainAll(currentIds)
        seenIdentities.addAll(currentIds)

        // audio: single channel, priority preemption
        if (top == null) {
            if (soundingIdentity != 0L) {
                audio.stopTone()
                soundingIdentity = 0L
            }
            return
        }
        if (top.identity == soundingIdentity) return // already sounding/sounded

        // rate-limit the polite chime; everything sharper always sounds
        val isAdvisoryChime = top.kind == AlertKind.HEADWAY && top.level == AlertLevel.ADVISORY
        if (isAdvisoryChime) {
            val now = System.currentTimeMillis()
            if (now - lastAdvisoryToneMs < advisoryCooldownMs) {
                soundingIdentity = top.identity // considered handled
                return
            }
            lastAdvisoryToneMs = now
        }

        audio.play(top.tone)
        if (top.speech.isNotEmpty()) audio.speak(top.speech)
        soundingIdentity = top.identity
    }

    fun reset() {
        audio.stopTone()
        soundingIdentity = 0L
        seenIdentities.clear()
        _topAlert.value = null
        _allAlerts.value = emptyList()
    }

    companion object {
        private const val TAG = "RAV-Alert"
    }
}
