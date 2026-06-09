package com.example.drones.waypoints

/**
 * One capture waypoint in the MARKER-LOCAL frame (meters; origin = scan center,
 * Z = height above floor). Mirrors DJI's waypoint fields (position + yaw +
 * gimbal + action) but in a local frame so it can be replayed indoors by our
 * virtual-stick executor, or converted to GPS/KMZ for outdoor DJI flights.
 */
data class CaptureWaypoint(
    val seq: Int,
    val xM: Double,
    val yM: Double,
    val zM: Double,            // height above floor
    val yawDeg: Double,        // heading to face center (marker-frame bearing)
    val gimbalPitchDeg: Double,
    val action: WaypointAction = WaypointAction.NONE
)

enum class WaypointAction { NONE, START_RECORD, STOP_RECORD, TAKE_PHOTO }

/**
 * An ordered capture path: the rings expanded into waypoints, plus the center
 * and metadata. Persisted as JSON; convertible to DJI KMZ (with a GPS anchor).
 */
data class WaypointPath(
    val id: String,
    val createdUtc: String,
    val centerXM: Double,
    val centerYM: Double,
    val waypoints: List<CaptureWaypoint>
)
