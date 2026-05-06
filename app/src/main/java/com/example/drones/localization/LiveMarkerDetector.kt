package com.example.drones.localization

import android.content.Context
import android.util.Log
import com.example.drones.sdk.VideoStreamManager
import com.example.drones.util.FileLogger
import dji.v5.manager.interfaces.ICameraStreamManager
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live ArUco marker detector. Runs on DJI NV21 frame stream.
 *
 * Lightweight — uses Y plane directly as grayscale Mat (no JPEG decode,
 * no RGB conversion). Throttled to 5fps. Drops frames if previous still
 * processing (prevents queue buildup).
 *
 * Mutually exclusive with LiveObjectDetector — only one should be active
 * at a time on the single shared DJI frame listener.
 */
class LiveMarkerDetector(
    private val context: Context,
    private val onMarkers: (List<MarkerObservation>) -> Unit,
    markerSizeM: Double = 0.20
) {
    companion object {
        private const val TAG = "LiveMarkerDetector"
        private const val MAX_FPS = 5
        private const val FRAME_INTERVAL_MS = 1000L / MAX_FPS
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "marker-thread").also { it.isDaemon = true }
    }

    private val detector = ArucoDetector(markerSizeM)
    private val running = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    private var lastFrameMs = 0L

    @Volatile var framesReceived = 0L; private set
    @Volatile var detectionsRun  = 0L; private set
    @Volatile var lastCount = 0;       private set

    fun start() {
        if (running.getAndSet(true)) return
        VideoStreamManager.addDecodedFrameListener(frameListener)
        FileLogger.write("LiveMarkerDetector started")
        Log.i(TAG, "Marker detection started")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        VideoStreamManager.removeDecodedFrameListener()
        FileLogger.write("LiveMarkerDetector stopped")
        Log.i(TAG, "Marker detection stopped")
    }

    private val frameListener = ICameraStreamManager.CameraFrameListener {
            frame, offset, length, width, height, _ ->

        if (!running.get()) return@CameraFrameListener

        val now = System.currentTimeMillis()
        if (now - lastFrameMs < FRAME_INTERVAL_MS) return@CameraFrameListener
        lastFrameMs = now
        framesReceived++

        if (width <= 0 || height <= 0 || length <= 0) return@CameraFrameListener
        if (!processing.compareAndSet(false, true)) return@CameraFrameListener

        // Grab Y plane only (first width*height bytes of NV21 = grayscale)
        val ySize = width * height
        val safeEnd = (offset + ySize).coerceAtMost(frame.size)
        if (safeEnd - offset < ySize) {
            processing.set(false)
            return@CameraFrameListener
        }
        val yCopy = frame.copyOfRange(offset, offset + ySize)

        executor.submit {
            try {
                val gray = Mat(height, width, CvType.CV_8UC1)
                gray.put(0, 0, yCopy)
                val obs = detector.detect(gray)
                gray.release()

                detectionsRun++
                lastCount = obs.size
                if (obs.isNotEmpty() && detectionsRun % 5L == 0L) {
                    val ids = obs.map { it.id }.joinToString(",")
                    val nearest = obs.minByOrNull { it.distanceM }
                    FileLogger.write("Markers seen: ids=[$ids] nearest=${nearest?.distanceM}m")
                }
                onMarkers(obs)
            } catch (e: Throwable) {
                Log.w(TAG, "Marker detect error: ${e.message}")
            } finally {
                processing.set(false)
            }
        }
    }
}
