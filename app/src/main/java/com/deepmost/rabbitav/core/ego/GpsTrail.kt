package com.deepmost.rabbitav.core.ego

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ring buffer of the last ~10 s of GPS fixes (Section 5.4) used to geotag
 * hazards BEHIND the current position (the wheel hit the hazard before the
 * phone reported it; Section 5.7).
 */
class GpsTrail(private val capacity: Int = 15) {

    class Fix(
        var timeMs: Long = 0,
        var lat: Double = 0.0,
        var lon: Double = 0.0,
        var speedMps: Float = 0f,
        var headingDeg: Float = 0f,
        var valid: Boolean = false,
    )

    private val ring = Array(capacity) { Fix() }
    private var head = 0 // next write position
    private val lock = Any()

    fun push(timeMs: Long, lat: Double, lon: Double, speedMps: Float, headingDeg: Float) {
        synchronized(lock) {
            val f = ring[head]
            f.timeMs = timeMs
            f.lat = lat
            f.lon = lon
            f.speedMps = speedMps
            f.headingDeg = headingDeg
            f.valid = true
            head = (head + 1) % capacity
        }
    }

    fun latest(): Fix? = synchronized(lock) {
        val idx = (head - 1 + capacity) % capacity
        ring[idx].takeIf { it.valid }?.copyOut()
    }

    /**
     * Walks the trail backwards from the newest fix, accumulating segment
     * lengths, and returns the interpolated point [metersBehind] the current
     * position. Falls back to dead reckoning along the reversed heading when
     * the trail is too short (fresh start).
     */
    fun positionBehind(metersBehind: Double): Pair<Double, Double>? {
        synchronized(lock) {
            val fixes = orderedNewestFirst()
            if (fixes.isEmpty()) return null
            if (metersBehind <= 0.0) return fixes[0].lat to fixes[0].lon
            var remaining = metersBehind
            for (i in 0 until fixes.size - 1) {
                val a = fixes[i]
                val b = fixes[i + 1]
                val seg = haversineMeters(a.lat, a.lon, b.lat, b.lon)
                if (seg >= remaining && seg > 0.01) {
                    val f = remaining / seg
                    return (a.lat + (b.lat - a.lat) * f) to (a.lon + (b.lon - a.lon) * f)
                }
                remaining -= seg
            }
            // trail exhausted: dead-reckon opposite the newest heading
            val newest = fixes[0]
            return offset(newest.lat, newest.lon, newest.headingDeg + 180f, remaining + trailLength(fixes))
                .let { (la, lo) ->
                    // we already walked trailLength; only remaining should be reckoned
                    // from the OLDEST fix along its reversed heading:
                    val oldest = fixes.last()
                    offset(oldest.lat, oldest.lon, oldest.headingDeg + 180f, remaining)
                }
        }
    }

    private fun trailLength(fixes: List<Fix>): Double {
        var d = 0.0
        for (i in 0 until fixes.size - 1) {
            d += haversineMeters(fixes[i].lat, fixes[i].lon, fixes[i + 1].lat, fixes[i + 1].lon)
        }
        return d
    }

    private fun orderedNewestFirst(): List<Fix> {
        val out = ArrayList<Fix>(capacity)
        for (i in 0 until capacity) {
            val idx = (head - 1 - i + 2 * capacity) % capacity
            val f = ring[idx]
            if (f.valid) out.add(f)
        }
        return out
    }

    fun clear() {
        synchronized(lock) {
            for (f in ring) f.valid = false
        }
    }

    private fun Fix.copyOut() = Fix(timeMs, lat, lon, speedMps, headingDeg, true)

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
        }

        /** Destination point given start, bearing, and distance. */
        fun offset(lat: Double, lon: Double, bearingDeg: Float, meters: Double): Pair<Double, Double> {
            val br = Math.toRadians(bearingDeg.toDouble())
            val dr = meters / EARTH_RADIUS_M
            val lat1 = Math.toRadians(lat)
            val lon1 = Math.toRadians(lon)
            val lat2 = Math.asin(sin(lat1) * cos(dr) + cos(lat1) * sin(dr) * cos(br))
            val lon2 = lon1 + atan2(sin(br) * sin(dr) * cos(lat1), cos(dr) - sin(lat1) * sin(lat2))
            return Math.toDegrees(lat2) to Math.toDegrees(lon2)
        }

        /** Smallest absolute difference between two bearings, degrees [0,180]. */
        fun bearingDeltaDeg(a: Float, b: Float): Float {
            var d = (a - b) % 360f
            if (d < -180f) d += 360f
            if (d > 180f) d -= 360f
            return kotlin.math.abs(d)
        }
    }
}
