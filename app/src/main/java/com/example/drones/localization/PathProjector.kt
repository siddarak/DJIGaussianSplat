package com.example.drones.localization

import android.graphics.PointF
import android.util.Log
import com.example.drones.orbit.EditableRing
import com.example.drones.orbit.MarkerPathPlanner
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/** One ring projected into image pixels (source-frame coords). */
data class ProjectedRing(
    val label: String,
    val pointsPx: List<PointF>,
    val colorArgb: Int,
    val index: Int = -1,
    val selected: Boolean = false,
    val locked: Boolean = false
)

/** The full planned path projected onto the current camera image. */
data class ProjectedPath(val rings: List<ProjectedRing>, val centerPx: PointF?)

/**
 * Projects the planned orbit rings (circles on the floor around the center)
 * onto the live camera image, so the operator sees where the drone will fly.
 *
 * Uses a ground→image homography from the visible markers: each marker gives
 * a (ground x,y) ↔ (image px) correspondence; ≥4 markers define the homography
 * of the floor plane. Ring footprints are then warped into image space.
 *
 * Recomputed per frame from live observations, so the overlay tracks as the
 * drone moves (works even when the scan center is locked — locked ground
 * positions paired with live image pixels).
 */
object PathProjector {

    private const val TAG = "PathProjector"
    private const val SAMPLES = 48
    private val RING_COLORS = intArrayOf(
        0xFF00E5FF.toInt(), 0xFF76FF03.toInt(), 0xFFFFD600.toInt(),
        0xFFE040FB.toInt(), 0xFFFF6D00.toInt()
    )

    /** Project editable ellipse rings (used by the per-ring editor). */
    fun projectEditable(
        observations: List<MarkerObservation>,
        scan: TopScanResult,
        rings: List<EditableRing>,
        selectedIndex: Int
    ): ProjectedPath? {
        val h = homography(observations, scan) ?: return null
        val projectedRings = rings.map { ring ->
            val pts = (0..SAMPLES).map { k ->
                val a = 2.0 * PI * k / SAMPLES
                Point(
                    scan.centerXM + ring.semiMajorM * cos(a),
                    scan.centerYM + ring.semiMinorM * sin(a)
                )
            }
            ProjectedRing(
                label = "R${ring.index + 1} ${"%.1f".format(ring.heightAboveFloorM)}m${if (ring.locked) " 🔒" else ""}",
                pointsPx = warp(pts, h),
                colorArgb = RING_COLORS[ring.index % RING_COLORS.size],
                index = ring.index,
                selected = ring.index == selectedIndex,
                locked = ring.locked
            )
        }
        val centerPx = warp(listOf(Point(scan.centerXM, scan.centerYM)), h).firstOrNull()
        return ProjectedPath(projectedRings, centerPx)
    }

    fun project(
        observations: List<MarkerObservation>,
        scan: TopScanResult,
        rings: List<MarkerPathPlanner.Ring>
    ): ProjectedPath? {
        val h = homography(observations, scan) ?: return null
        val projectedRings = rings.mapIndexed { i, ring ->
            val pts = (0..SAMPLES).map { k ->
                val a = 2.0 * PI * k / SAMPLES
                Point(scan.centerXM + ring.radiusM * cos(a), scan.centerYM + ring.radiusM * sin(a))
            }
            ProjectedRing(ring.label, warp(pts, h), RING_COLORS[i % RING_COLORS.size], index = i)
        }
        val centerPx = warp(listOf(Point(scan.centerXM, scan.centerYM)), h).firstOrNull()
        return ProjectedPath(projectedRings, centerPx)
    }

    /** Compute ground→image homography from visible markers (>=4 needed). */
    private fun homography(observations: List<MarkerObservation>, scan: TopScanResult): org.opencv.core.Mat? {
        val groundById = scan.markers.associateBy { it.id }
        val src = ArrayList<Point>()
        val dst = ArrayList<Point>()
        observations.forEach { obs ->
            val g = groundById[obs.id] ?: return@forEach
            val c = obs.centerPx
            src.add(Point(g.xM, g.yM))
            dst.add(Point(c.x.toDouble(), c.y.toDouble()))
        }
        if (src.size < 4) return null
        return try {
            val h = Calib3d.findHomography(MatOfPoint2f(*src.toTypedArray()), MatOfPoint2f(*dst.toTypedArray()))
            if (h.empty()) null else h
        } catch (e: Exception) {
            Log.w(TAG, "findHomography fail: ${e.message}"); null
        }
    }

    private fun warp(groundPts: List<Point>, h: org.opencv.core.Mat): List<PointF> {
        val inM = MatOfPoint2f(*groundPts.toTypedArray())
        val outM = MatOfPoint2f()
        Core.perspectiveTransform(inM, outM, h)
        return outM.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) }
    }
}
