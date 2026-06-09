package com.example.drones.waypoints

import com.example.drones.localization.TopScanResult
import com.example.drones.orbit.EditableRing
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Expands the edited rings into an ordered list of capture waypoints around the
 * center. Each waypoint faces the center and uses the ring's gimbal pitch.
 */
object WaypointBuilder {

    private const val PER_RING = 16

    fun build(scan: TopScanResult, rings: List<EditableRing>): List<CaptureWaypoint> {
        val out = ArrayList<CaptureWaypoint>()
        var seq = 0
        rings.sortedBy { it.heightAboveFloorM }.forEach { ring ->
            for (k in 0 until PER_RING) {
                val a = 2.0 * PI * k / PER_RING
                val x = scan.centerXM + ring.semiMajorM * cos(a)
                val y = scan.centerYM + ring.semiMinorM * sin(a)
                // yaw to face center (marker-frame bearing from waypoint to center)
                val yaw = Math.toDegrees(atan2(scan.centerYM - y, scan.centerXM - x))
                val action = when {
                    seq == 0 -> WaypointAction.START_RECORD
                    else -> WaypointAction.NONE
                }
                out.add(CaptureWaypoint(seq++, x, y, ring.heightAboveFloorM, yaw, ring.gimbalPitchDeg, action))
            }
        }
        if (out.isNotEmpty()) {
            out[out.size - 1] = out.last().copy(action = WaypointAction.STOP_RECORD)
        }
        return out
    }
}
