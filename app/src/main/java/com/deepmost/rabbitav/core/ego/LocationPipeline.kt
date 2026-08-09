package com.deepmost.rabbitav.core.ego

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * FusedLocationProvider at 1 Hz, high accuracy while driving (Section 2).
 * Permission is checked by the service before start; SecurityException is
 * still caught defensively (user can revoke mid-drive).
 */
@Singleton
class LocationPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val egoEstimator: EgoEstimator,
) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var active = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            egoEstimator.feedLocation(loc)
        }
    }

    @SuppressLint("MissingPermission") // caller enforces; revocation handled below
    fun start() {
        if (active) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            active = true
            Timber.tag(TAG).i("location updates started (1 Hz, high accuracy)")
        } catch (se: SecurityException) {
            Timber.tag(TAG).e(se, "location permission missing; ego speed unavailable")
        }
    }

    fun stop() {
        if (!active) return
        client.removeLocationUpdates(callback)
        active = false
        Timber.tag(TAG).i("location updates stopped")
    }

    companion object {
        private const val TAG = "RAV-Ego"
    }
}
