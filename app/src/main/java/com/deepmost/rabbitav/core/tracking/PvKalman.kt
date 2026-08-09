package com.deepmost.rabbitav.core.tracking

/**
 * 1D constant-velocity Kalman filter (position + velocity). Four of these make
 * up a box tracker (cx, cy, w, h) — mathematically identical to the usual 8x8
 * block-diagonal formulation but with no matrix library and no allocation.
 * Also reused for the per-track distance filter [Z, dZ/dt] (Section 5.4).
 *
 * Process noise follows the continuous white-acceleration model:
 * Q = q * [[dt^3/3, dt^2/2], [dt^2/2, dt]].
 */
class PvKalman(
    /** Process noise intensity q (accel^2 per unit time). Larger tracks maneuvers faster. */
    private val processNoise: Float,
    /** Default measurement variance R. */
    private val measurementNoise: Float,
) {
    var p = 0f; private set // position
    var v = 0f; private set // velocity

    // covariance (symmetric): [P00 P01; P01 P11]
    private var p00 = 1f
    private var p01 = 0f
    private var p11 = 1f

    private var initialized = false
    val isInitialized: Boolean get() = initialized

    fun reset(position: Float, velocity: Float = 0f, posVar: Float = 1f, velVar: Float = 10f) {
        p = position
        v = velocity
        p00 = posVar
        p01 = 0f
        p11 = velVar
        initialized = true
    }

    fun predict(dt: Float) {
        if (!initialized || dt <= 0f) return
        p += v * dt
        val q = processNoise
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val n00 = p00 + 2f * dt * p01 + dt2 * p11 + q * dt3 / 3f
        val n01 = p01 + dt * p11 + q * dt2 / 2f
        val n11 = p11 + q * dt
        p00 = n00
        p01 = n01
        p11 = n11
    }

    /** Measurement update with optional per-call variance override. */
    fun update(z: Float, r: Float = measurementNoise) {
        if (!initialized) {
            reset(z)
            return
        }
        val s = p00 + r
        if (s <= 1e-9f) return
        val k0 = p00 / s
        val k1 = p01 / s
        val innov = z - p
        p += k0 * innov
        v += k1 * innov
        val n00 = (1f - k0) * p00
        val n01 = (1f - k0) * p01
        val n11 = p11 - k1 * p01
        p00 = n00
        p01 = n01
        p11 = n11
    }

    /** Position variance — used to widen gates for stale tracks. */
    val positionVariance: Float get() = p00
}
