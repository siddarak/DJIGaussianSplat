package com.example.drones.orbit

import com.example.drones.localization.TopScanResult
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.atan2
import kotlin.math.PI

/**
 * Turns a locked top-scan + object/table heights into the dome rings.
 *
 * Mixture of dome (radius shrinks as height rises) + keep-out clamp:
 *   r_k = D·cos(elevation),  clamped to >= keep-out (never enter inner perimeter)
 *   h_k = objectCenterZ + D·sin(elevation)   (height above floor)
 *   gimbal aims at the object center.
 *
 * All distances meters; angles degrees. Frame: ground plane, center at
 * (scan.centerXM, scan.centerYM), Z up from floor.
 */
object MarkerPathPlanner {

    data class Ring(
        val radiusM: Double,
        val heightAboveFloorM: Double,
        val gimbalPitchDeg: Double,
        val label: String
    )

    private val ELEVATIONS_DEG = listOf(20.0, 45.0, 70.0)

    fun planRings(
        scan: TopScanResult,
        objectHeightM: Double,
        tableHeightM: Double
    ): List<Ring> {
        val objectCenterZ = tableHeightM + objectHeightM / 2.0
        val keepout = scan.rKeepoutM
        // Slant radius so the lowest ring sits near the outer perimeter.
        val baseR = maxOf(scan.rOuterM, keepout + 0.5)
        val slant = baseR / cos(Math.toRadians(ELEVATIONS_DEG.first()))

        return ELEVATIONS_DEG.mapIndexed { i, elevDeg ->
            val e = Math.toRadians(elevDeg)
            val r = (slant * cos(e)).coerceAtLeast(keepout)
            val h = objectCenterZ + slant * sin(e)
            val pitch = -Math.toDegrees(atan2(h - objectCenterZ, r)).coerceIn(-90.0, 35.0)
            Ring(
                radiusM = r,
                heightAboveFloorM = h,
                gimbalPitchDeg = pitch,
                label = "R${i + 1} %.1fm h%.1f".format(r, h)
            )
        }
    }

    /** Seed editable (initially circular) rings from the plan, for the per-ring editor. */
    fun planEditableRings(
        scan: TopScanResult,
        objectHeightM: Double,
        tableHeightM: Double
    ): List<EditableRing> = planRings(scan, objectHeightM, tableHeightM).mapIndexed { i, r ->
        EditableRing(
            index = i,
            semiMajorM = r.radiusM,
            semiMinorM = r.radiusM,
            heightAboveFloorM = r.heightAboveFloorM,
            gimbalPitchDeg = r.gimbalPitchDeg,
            locked = false
        )
    }
}
