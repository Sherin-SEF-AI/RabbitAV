package com.deepmost.rabbitav.core.tracking

import com.deepmost.rabbitav.core.inference.CanonicalClass

/**
 * Immutable per-tick view of a track for alert engines and the HUD overlay.
 * Produced at 25 Hz; small and short-lived by design.
 */
data class TrackSnapshot(
    val id: Int,
    val canonical: CanonicalClass,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val confirmed: Boolean,
    val hits: Int,
    val score: Float,
    val zMeters: Float,
    val closingMps: Float,
    val ttcS: Float,
    val lateralXM: Float,
    val inCorridor: Boolean,
    val corridorForS: Float,
    val distanceLowConfidence: Boolean,
    val headwayS: Float,
    val coastingForS: Float,
)
