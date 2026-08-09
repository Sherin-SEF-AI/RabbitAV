package com.deepmost.rabbitav.core.hazard

/**
 * Minimal geohash (encode/decode/neighbors) in pure Kotlin. Precision 7 cells
 * are ~153 m x 153 m — the HazardSite index granularity (Section 5.7);
 * clustering queries scan the center cell + 8 neighbors then filter by exact
 * haversine distance.
 */
object Geohash {

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    fun encode(lat: Double, lon: Double, precision: Int = 7): String {
        var latLo = -90.0
        var latHi = 90.0
        var lonLo = -180.0
        var lonHi = 180.0
        val sb = StringBuilder(precision)
        var bit = 0
        var ch = 0
        var even = true
        while (sb.length < precision) {
            if (even) {
                val mid = (lonLo + lonHi) / 2
                if (lon >= mid) {
                    ch = (ch shl 1) or 1
                    lonLo = mid
                } else {
                    ch = ch shl 1
                    lonHi = mid
                }
            } else {
                val mid = (latLo + latHi) / 2
                if (lat >= mid) {
                    ch = (ch shl 1) or 1
                    latLo = mid
                } else {
                    ch = ch shl 1
                    latHi = mid
                }
            }
            even = !even
            if (++bit == 5) {
                sb.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }
        return sb.toString()
    }

    /** Center (lat, lon) and half-sizes (dLat, dLon) of a geohash cell. */
    fun decode(hash: String): DoubleArray {
        var latLo = -90.0
        var latHi = 90.0
        var lonLo = -180.0
        var lonHi = 180.0
        var even = true
        for (c in hash) {
            val cd = BASE32.indexOf(c)
            require(cd >= 0) { "bad geohash char $c" }
            for (mask in intArrayOf(16, 8, 4, 2, 1)) {
                if (even) {
                    val mid = (lonLo + lonHi) / 2
                    if (cd and mask != 0) lonLo = mid else lonHi = mid
                } else {
                    val mid = (latLo + latHi) / 2
                    if (cd and mask != 0) latLo = mid else latHi = mid
                }
                even = !even
            }
        }
        return doubleArrayOf(
            (latLo + latHi) / 2,
            (lonLo + lonHi) / 2,
            (latHi - latLo) / 2,
            (lonHi - lonLo) / 2,
        )
    }

    /** The cell plus its 8 neighbors (offset-and-reencode; exact enough away
     *  from the poles, which Indian roads comfortably are). */
    fun withNeighbors(hash: String): List<String> {
        val (lat, lon, dLat, dLon) = decode(hash)
        val out = ArrayList<String>(9)
        for (dy in -1..1) {
            for (dx in -1..1) {
                val nl = (lat + dy * 2 * dLat).coerceIn(-90.0, 90.0)
                val no = normalizeLon(lon + dx * 2 * dLon)
                out.add(encode(nl, no, hash.length))
            }
        }
        return out.distinct()
    }

    private fun normalizeLon(lon: Double): Double {
        var l = lon
        while (l > 180.0) l -= 360.0
        while (l < -180.0) l += 360.0
        return l
    }

    private operator fun DoubleArray.component1() = this[0]
    private operator fun DoubleArray.component2() = this[1]
    private operator fun DoubleArray.component3() = this[2]
    private operator fun DoubleArray.component4() = this[3]
}
