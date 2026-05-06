package com.example.drones.localization

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import kotlin.math.PI
import kotlin.math.tan

/**
 * Camera intrinsic parameters for the DJI Mini 4 Pro main camera.
 *
 * Hardcoded defaults are derived from the published FOV and resolution.
 * These are rough — for accurate ArUco pose estimation, replace with
 * a one-time calibration result (cameraMatrix + distCoeffs computed by
 * OpenCV's checkerboard calibration utility).
 *
 * Mini 4 Pro: 1/1.3" CMOS, 24mm equivalent, 82.1° diagonal FOV.
 * For a 16:9 1920x1080 stream → HFOV ≈ 73°, VFOV ≈ 44°.
 */
object CameraIntrinsics {

    // Default frame resolution (matches video stream)
    const val DEFAULT_WIDTH = 1920
    const val DEFAULT_HEIGHT = 1080

    // Mini 4 Pro 16:9 horizontal FOV in radians
    private const val HFOV_RAD = 73.0 * PI / 180.0

    /**
     * 3x3 camera matrix:
     *   [ fx  0  cx ]
     *   [  0 fy  cy ]
     *   [  0  0   1 ]
     */
    val cameraMatrix: Mat by lazy {
        val w = DEFAULT_WIDTH
        val h = DEFAULT_HEIGHT
        val fx = w / (2.0 * tan(HFOV_RAD / 2.0))
        val fy = fx                 // pixels are square — fy ≈ fx for non-anamorphic sensors
        val cx = w / 2.0
        val cy = h / 2.0
        Mat(3, 3, CvType.CV_64F).apply {
            put(0, 0, fx, 0.0, cx)
            put(1, 0, 0.0, fy, cy)
            put(2, 0, 0.0, 0.0, 1.0)
        }
    }

    /**
     * Distortion coefficients [k1, k2, p1, p2, k3].
     * Zero by default — Mini 4 Pro applies internal lens correction
     * before streaming, so frames are already mostly rectified.
     * Calibration can refine these.
     */
    val distCoeffs: MatOfDouble by lazy {
        MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0)
    }

    /** Computed focal length in pixels. */
    val focalPx: Double get() = cameraMatrix[0, 0][0]
}
