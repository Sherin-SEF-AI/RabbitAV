package com.deepmost.rabbitav.core.governor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Thermal pressure input for the governor (Section 5.10), best source first:
 * getThermalHeadroom (API 30+), THERMAL_STATUS listener (API 29), battery
 * temperature sticky intent (< 29).
 *
 * Output scale: 0.0 = cool, 1.0 = throttling imminent, >1.0 = throttling.
 */
@Singleton
class ThermalMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Volatile private var listenerStatus = PowerManager.THERMAL_STATUS_NONE
    private var listenerRegistered = false
    private var lastHeadroomCallMs = 0L
    @Volatile private var cachedHeadroom = Float.NaN

    fun start() {
        if (Build.VERSION.SDK_INT >= 29 && !listenerRegistered) {
            try {
                powerManager.addThermalStatusListener { status -> listenerStatus = status }
                listenerRegistered = true
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "thermal status listener unavailable")
            }
        }
    }

    /**
     * Normalized pressure. getThermalHeadroom is rate-limited by the platform,
     * so it is polled at most every 10 s and cached between calls.
     */
    fun pressure(): Float {
        if (Build.VERSION.SDK_INT >= 30) {
            val now = System.currentTimeMillis()
            if (now - lastHeadroomCallMs >= HEADROOM_POLL_MS) {
                lastHeadroomCallMs = now
                cachedHeadroom = try {
                    // forecastSeconds=10: near-future headroom
                    powerManager.getThermalHeadroom(10)
                } catch (t: Throwable) {
                    Float.NaN
                }
            }
            if (!cachedHeadroom.isNaN() && cachedHeadroom > 0f) {
                // headroom 1.0 == severe throttling threshold
                return cachedHeadroom
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return when (listenerStatus) {
                PowerManager.THERMAL_STATUS_NONE -> 0.3f
                PowerManager.THERMAL_STATUS_LIGHT -> 0.7f
                PowerManager.THERMAL_STATUS_MODERATE -> 0.95f
                else -> 1.2f // SEVERE and above
            }
        }
        // Pre-29: battery temperature via sticky intent (no receiver needed).
        val temp = batteryTempC()
        return when {
            temp.isNaN() -> 0.5f // unknown: assume mild pressure
            temp < 36f -> 0.3f
            temp < 40f -> 0.7f
            temp < 43f -> 0.95f
            else -> 1.2f
        }
    }

    fun batteryTempC(): Float {
        val intent: Intent? = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val t = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return if (t == Int.MIN_VALUE) Float.NaN else t / 10f
    }

    companion object {
        private const val TAG = "RAV-Gov"

        /** getThermalHeadroom is platform-rate-limited (~1 call/10 s). */
        const val HEADROOM_POLL_MS = 10_000L
    }
}
