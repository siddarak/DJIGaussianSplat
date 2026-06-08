package com.example.drones.localization

import android.graphics.PointF
import android.util.Log
import com.example.drones.orbit.MarkerPathPlanner
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/** One ring projected into image pixels (source-frame coords). */
data class ProjectedRing(val label: String, val pointsPx: List<PointF>, val colorArgb: Int)

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

    fun project(
        observations: List<MarkerObservation>,
        scan: TopScanResult,
        rings: List<MarkerPathPlanner.Ring>
    ): ProjectedPath? {
        // Pair each live observation (image px) with its locked ground position (by id).
        val groundById = scan.markers.associateBy { it.id }
        val src = ArrayList<Point>()   // ground (x, y)
        val dst = ArrayList<Point>()   // image (px, py)
        observations.forEach { obs ->
            val g = groundById[obs.id] ?: return@forEach
            val c = obs.centerPx
            src.add(Point(g.xM, g.yM))
            dst.add(Point(c.x.toDouble(), c.y.toDouble()))
        }
        if (src.size < 4) return null   // need 4+ for a homography

        val srcMat = MatOfPoint2f(*src.toTypedArray())
        val dstMat = MatOfPoint2f(*dst.toTypedArray())
        val h = try {
            Calib3d.findHomography(srcMat, dstMat)
        } catch (e: Exception) {
            Log.w(TAG, "findHomography fail: ${e.message}"); null
        } ?: return null
        if (h.empty()) return null

        fun warp(groundPts: List<Point>): List<PointF> {
            val inM = MatOfPoint2f(*groundPts.toTypedArray())
            val outM = MatOfPoint2f()
            Core.perspectiveTransform(inM, outM, h)
            return outM.toArray().map { PointF(it.x.toFloat(), it.y.toFloat()) }
        }

        // Ring footprints: circle of radius r around center on the floor.
        val projectedRings = rings.mapIndexed { i, ring ->
            val pts = (0..SAMPLES).map { k ->
                val a = 2.0 * PI * k / SAMPLES
                Point(scan.centerXM + ring.radiusM * cos(a), scan.centerYM + ring.radiusM * sin(a))
            }
            ProjectedRing(ring.label, warp(pts), RING_COLORS[i % RING_COLORS.size])
        }

        val centerPx = warp(listOf(Point(scan.centerXM, scan.centerYM))).firstOrNull()
        return ProjectedPath(projectedRings, centerPx)
    }
}
