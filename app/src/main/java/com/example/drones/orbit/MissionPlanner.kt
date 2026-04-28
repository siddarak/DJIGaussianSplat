package com.example.drones.orbit

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos

/**
 * Computes the full set of orbit rings from a mission spec.
 *
 * Ring layout (bottom to top):
 *   LOW        → below lock altitude (captures upward-looking views)
 *   EQUATORIAL → lock altitude (sides of object)
 *   MID / HIGH → ascending rings until object top
 *   TOP_SHOT   → directly above, camera pointing straight down
 *
 * Number of ascending rings auto-scales with objectHeight / altitudeStepM.
 * For a 1m object at 0.6m step: LOW + EQ + 1 MID + TOP = 4 rings.
 * For a 2m object at 0.6m step: LOW + EQ + 3 rings + TOP = 6 rings.
 */
object MissionPlanner {

    /**
     * Returns rings ordered bottom → top.
     * The drone starts at the EQUATORIAL ring (it's already there at lock time).
     * Execution order: EQUATORIAL → LOW → EQUATORIAL → MID → HIGH → TOP_SHOT
     * This means the first move is a short descent, then the full ascent.
     */
    fun planRings(mission: OrbitMission): List<OrbitRing> {
        val rings = mutableListOf<OrbitRing>()

        // LOW ring — brief descent for upward-looking coverage
        if (mission.lowOrbitEnabled) {
            val alt = (mission.lockAlt + mission.lowOrbitOffsetM)
                .coerceAtLeast(MIN_SAFE_ALTITUDE_M)
            rings.add(
                OrbitRing(
                    altitude = alt,
                    gimbalPitch = gimbalPitch(alt, mission.centerAlt, mission.orbitRadius),
                    phase = OrbitPhase.LOW,
                    label = "Low"
                )
            )
        }

        // EQUATORIAL ring — lock altitude
        rings.add(
            OrbitRing(
                altitude = mission.lockAlt,
                gimbalPitch = gimbalPitch(mission.lockAlt, mission.centerAlt, mission.orbitRadius),
                phase = OrbitPhase.EQUATORIAL,
                label = "Equatorial"
            )
        )

        // Ascending rings from lock+step to object top.
        // objectTopAlt is relative to lockAlt — rings must climb objectHeight meters above start.
        val objectTopAlt = mission.lockAlt + mission.objectHeight
        var alt = mission.lockAlt + mission.altitudeStepM
        var ringNum = 1
        while (alt < objectTopAlt - mission.altitudeStepM * 0.4) {
            val phase = if (alt < mission.centerAlt + mission.objectHeight * 0.55)
                OrbitPhase.MID else OrbitPhase.HIGH
            rings.add(
                OrbitRing(
                    altitude = alt,
                    gimbalPitch = gimbalPitch(alt, mission.centerAlt, mission.orbitRadius),
                    phase = phase,
                    label = "Ring $ringNum"
                )
            )
            alt += mission.altitudeStepM
            ringNum++
        }

        // TOP_SHOT — hover above, camera straight down
        if (mission.topShotEnabled) {
            val topAlt = objectTopAlt + 0.5
            rings.add(
                OrbitRing(
                    altitude = topAlt,
                    gimbalPitch = -85.0,   // Mini 4 Pro max downward pitch
                    phase = OrbitPhase.TOP_SHOT,
                    label = "Top"
                )
            )
        }

        return rings
    }

    /**
     * Execution order: start at equatorial, dip to low, return, then ascend.
     * Returns rings in the order they should be flown.
     */
    fun executionOrder(rings: List<OrbitRing>): List<OrbitRing> {
        val eq = rings.firstOrNull { it.phase == OrbitPhase.EQUATORIAL }
            ?: return rings  // fallback: fly rings as-planned if planner is misconfigured
        val low = rings.filter { it.phase == OrbitPhase.LOW }
        val ascending = rings.filter { it.phase != OrbitPhase.EQUATORIAL && it.phase != OrbitPhase.LOW }
        // EQ → LOW → EQ → ascending...
        return listOf(eq) + low + listOf(eq) + ascending
    }

    /**
     * Compute object center GPS from drone's lock position.
     *
     * At lock time the drone is at (droneLat, droneLon) facing headingDeg,
     * and the obstacle sensor reads distanceM to the object face.
     * The object center is distanceM directly ahead.
     */
    fun computeCenter(
        droneLat: Double,
        droneLon: Double,
        headingDeg: Double,
        distanceM: Double,
        objectHeightM: Double
    ): Triple<Double, Double, Double> {
        val headingRad = Math.toRadians(headingDeg)
        val dLat = distanceM * cos(headingRad) / METERS_PER_DEGREE_LAT
        val dLon = distanceM * Math.sin(headingRad) /
                (METERS_PER_DEGREE_LAT * cos(Math.toRadians(droneLat)))
        // centerAlt = half the object height above ground (altitude 0 = takeoff point).
        // Gimbal pitch is computed relative to this so it actually looks at the object center.
        val centerAlt = objectHeightM / 2.0
        return Triple(droneLat + dLat, droneLon + dLon, centerAlt)
    }

    /**
     * Starting angle of the drone relative to the object center.
     * This is the heading + 180° (drone is opposite side of where it's pointing).
     */
    fun startAngleDeg(headingDeg: Double): Double = (headingDeg + 180.0) % 360.0

    // --- private ---

    private fun gimbalPitch(droneAlt: Double, centerAlt: Double, radius: Double): Double {
        val altDelta = droneAlt - centerAlt
        // Negative = looking down, positive = looking up
        return (-Math.toDegrees(atan2(altDelta, radius))).coerceIn(-85.0, 35.0)
    }

    private const val METERS_PER_DEGREE_LAT = 111_111.0
    private const val MIN_SAFE_ALTITUDE_M = 0.3
}
