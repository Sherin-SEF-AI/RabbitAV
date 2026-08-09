package com.deepmost.rabbitav.core.inference

/**
 * The only classes the app logic ever sees. Model-specific label sets are mapped
 * into these via the sidecar's classMap; anything unmapped is dropped before
 * tracking.
 *
 * [widthMeters] is the physical width prior used by the width-prior distance
 * cross-check (Section 5.4). Sane range 0.4–3.0 m.
 */
enum class CanonicalClass(
    val widthMeters: Float,
    val isVru: Boolean,
    val isVehicle: Boolean,
    val isRoadHazard: Boolean,
) {
    PEDESTRIAN(0.55f, isVru = true, isVehicle = false, isRoadHazard = false),
    CYCLIST(0.60f, isVru = true, isVehicle = false, isRoadHazard = false),
    CAR(1.75f, isVru = false, isVehicle = true, isRoadHazard = false),
    MOTORCYCLE(0.80f, isVru = false, isVehicle = true, isRoadHazard = false),
    AUTO_RICKSHAW(1.40f, isVru = false, isVehicle = true, isRoadHazard = false),
    BUS(2.50f, isVru = false, isVehicle = true, isRoadHazard = false),
    TRUCK(2.50f, isVru = false, isVehicle = true, isRoadHazard = false),
    ANIMAL(1.60f, isVru = true, isVehicle = false, isRoadHazard = false),
    POTHOLE(0.8f, isVru = false, isVehicle = false, isRoadHazard = true),
    SPEED_BREAKER(3.0f, isVru = false, isVehicle = false, isRoadHazard = true),
    WATERLOGGING(2.0f, isVru = false, isVehicle = false, isRoadHazard = true),
    UNKNOWN(1.0f, isVru = false, isVehicle = false, isRoadHazard = false),
    ;

    /** Obstacle classes that participate in FCW/corridor logic. */
    val isObstacle: Boolean get() = isVru || isVehicle

    companion object {
        fun fromName(name: String): CanonicalClass =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN
    }
}
