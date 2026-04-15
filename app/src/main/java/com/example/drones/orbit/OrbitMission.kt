package com.example.drones.orbit

/**
 * Complete description of a hemispherical orbit mission.
 * Created at lock time from drone telemetry + obstacle sensor reading.
 */
data class OrbitMission(
    // --- Object center (computed from lock position + heading + sensor distance) ---
    val centerLat: Double,
    val centerLon: Double,
    val centerAlt: Double,          // altitude of object center (= lock altitude for now)

    // --- Geometry ---
    val orbitRadius: Double,        // horizontal radius in meters (from obstacle sensor)
    val objectHeight: Double,       // estimated object height in meters (user input)
    val lockAlt: Double,            // drone altitude at lock time = equatorial ring altitude
    val startAngleDeg: Double,      // drone's bearing FROM center at lock (0=North, CW)

    // --- Orbit behavior ---
    val speed: Double = 2.0,                             // m/s
    val direction: OrbitDirection = OrbitDirection.CLOCKWISE,
    val altitudeStepM: Double = 0.6,                     // meters between rings
    val lowOrbitEnabled: Boolean = true,
    val lowOrbitOffsetM: Double = -0.5,                  // below lock altitude
    val topShotEnabled: Boolean = true,
)

enum class OrbitDirection { CLOCKWISE, COUNTERCLOCKWISE }

/**
 * One altitude ring within the mission.
 */
data class OrbitRing(
    val altitude: Double,       // absolute altitude (same reference as telemetry)
    val gimbalPitch: Double,    // degrees — negative = looking down
    val phase: OrbitPhase,
    val label: String
)

enum class OrbitPhase { LOW, EQUATORIAL, MID, HIGH, TOP_SHOT }

/**
 * Observable state machine for the orbit executor.
 */
sealed class OrbitState {
    object Idle : OrbitState()
    object Arming : OrbitState()
    data class Flying(
        val ring: OrbitRing,
        val ringIndex: Int,
        val totalRings: Int,
        val progressDeg: Double          // 0–360
    ) : OrbitState()
    data class Climbing(
        val fromAlt: Double,
        val toAlt: Double
    ) : OrbitState()
    object TopShot : OrbitState()
    object Done : OrbitState()
    object Aborted : OrbitState()
    data class Error(val message: String) : OrbitState()
}
