package com.example.drones.localization

import android.graphics.PointF
import android.util.Log
import com.example.drones.util.FileLogger
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.objdetect.ArucoDetector as OcvArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Dictionary
import org.opencv.objdetect.Objdetect

/**
 * Detects ArUco markers in NV21 frames + estimates 6DoF pose per marker.
 *
 * Dictionary: DICT_4X4_50 — 50 unique 4x4 markers, fast detection.
 * Marker physical size set globally (assumes all markers same size for now).
 *
 * Thread-safe: detect() can be called from any thread.
 */
class ArucoDetector(
    private val markerSizeM: Double = 0.20    // 20cm default. Configurable per environment.
) {
    companion object {
        private const val TAG = "ArucoDetector"

        @Volatile private var openCvReady = false

        /** Call once at app startup before any detect(). */
        fun initOpenCv() {
            if (openCvReady) return
            try {
                val ok = OpenCVLoader.initLocal()
                openCvReady = ok
                FileLogger.write("OpenCV init: $ok ver=${org.opencv.core.Core.VERSION}")
                Log.i(TAG, "OpenCV initLocal=$ok ver=${org.opencv.core.Core.VERSION}")
            } catch (e: Throwable) {
                FileLogger.write("OpenCV init FAIL: ${e.message}")
                Log.e(TAG, "OpenCV init failed", e)
            }
        }
    }

    private val dictionary: Dictionary by lazy {
        Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)
    }
    private val params: DetectorParameters by lazy { DetectorParameters() }
    private val detector: OcvArucoDetector by lazy {
        OcvArucoDetector(dictionary, params)
    }

    /**
     * Detect markers in a grayscale frame.
     * @param grayMat single-channel CV_8U Mat
     */
    fun detect(grayMat: Mat): List<MarkerObservation> {
        if (!openCvReady) return emptyList()

        val corners = mutableListOf<Mat>()
        val ids = Mat()
        try {
            detector.detectMarkers(grayMat, corners, ids)
        } catch (e: Throwable) {
            Log.w(TAG, "detectMarkers fail: ${e.message}")
            return emptyList()
        }
        if (ids.empty() || corners.isEmpty()) return emptyList()

        val cm = CameraIntrinsics.cameraMatrix
        val dc = CameraIntrinsics.distCoeffs

        // Build 3D model of marker (centered at origin, in marker's own coord frame)
        val half = (markerSizeM / 2.0).toFloat()
        val objectPts = MatOfPoint3f(
            Point3(-half.toDouble(),  half.toDouble(), 0.0),
            Point3( half.toDouble(),  half.toDouble(), 0.0),
            Point3( half.toDouble(), -half.toDouble(), 0.0),
            Point3(-half.toDouble(), -half.toDouble(), 0.0)
        )

        val results = mutableListOf<MarkerObservation>()
        val n = corners.size
        for (i in 0 until n) {
            val markerId = ids[i, 0][0].toInt()
            val cornerMat = corners[i]   // 1x4 CV_32FC2

            val pts = arrayOf(PointF(), PointF(), PointF(), PointF())
            for (k in 0 until 4) {
                val v = cornerMat[0, k]
                pts[k] = PointF(v[0].toFloat(), v[1].toFloat())
            }

            // solvePnP: image points → camera-to-marker pose
            val imagePts = MatOfPoint2f(
                Point(pts[0].x.toDouble(), pts[0].y.toDouble()),
                Point(pts[1].x.toDouble(), pts[1].y.toDouble()),
                Point(pts[2].x.toDouble(), pts[2].y.toDouble()),
                Point(pts[3].x.toDouble(), pts[3].y.toDouble())
            )
            val rvec = Mat()
            val tvec = Mat()
            val ok = try {
                Calib3d.solvePnP(objectPts, imagePts, cm, dc, rvec, tvec, false, Calib3d.SOLVEPNP_IPPE_SQUARE)
            } catch (e: Throwable) {
                false
            }
            if (!ok) continue

            results.add(MarkerObservation(
                id = markerId,
                cornersPx = pts,
                tvec = doubleArrayOf(tvec[0, 0][0], tvec[1, 0][0], tvec[2, 0][0]),
                rvec = doubleArrayOf(rvec[0, 0][0], rvec[1, 0][0], rvec[2, 0][0])
            ))

            imagePts.release()
            rvec.release()
            tvec.release()
        }

        objectPts.release()
        ids.release()
        corners.forEach { it.release() }
        return results
    }
}
