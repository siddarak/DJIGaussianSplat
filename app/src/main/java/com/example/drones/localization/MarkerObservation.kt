package com.example.drones.localization

import android.graphics.PointF

/**
 * One ArUco marker as observed in a single video frame.
 *
 * @param id ArUco dictionary ID
 * @param cornersPx 4 corners in image pixels (top-left, top-right, bottom-right, bottom-left)
 * @param tvec marker position in camera coords (x, y, z) in meters
 * @param rvec marker rotation as Rodrigues vector (rx, ry, rz)
 */
data class MarkerObservation(
    val id: Int,
    val cornersPx: Array<PointF>,
    val tvec: DoubleArray,
    val rvec: DoubleArray,
    val timestampMs: Long = System.currentTimeMillis()
) {
    /** Distance from camera to marker in meters. */
    val distanceM: Double
        get() = kotlin.math.sqrt(tvec[0] * tvec[0] + tvec[1] * tvec[1] + tvec[2] * tvec[2])

    /** Center of marker in image pixels. */
    val centerPx: PointF
        get() = PointF(
            cornersPx.map { it.x }.average().toFloat(),
            cornersPx.map { it.y }.average().toFloat()
        )
}
