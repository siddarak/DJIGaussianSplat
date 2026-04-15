package com.example.drones.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.util.Log
import com.example.drones.sdk.VideoStreamManager
import dji.v5.manager.interfaces.ICameraStreamManager
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs EfficientDet-Lite0 (COCO, 80 classes) on the live drone video feed.
 *
 * Frame source: VideoStreamManager.addDecodedFrameListener() — NV21 YUV frames.
 * Inference rate: throttled to MAX_FPS (default 8) to avoid thermal throttle.
 * Inference thread: dedicated single-thread executor (off main thread).
 *
 * Usage:
 *   val detector = LiveObjectDetector(context) { results -> ... }
 *   detector.start()   // when drone connects
 *   detector.stop()    // when drone disconnects / app pauses
 */
class LiveObjectDetector(
    private val context: Context,
    private val onResults: (List<DetectionResult>) -> Unit
) {
    companion object {
        private const val TAG = "LiveObjectDetector"
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
        private const val MAX_RESULTS = 5
        private const val SCORE_THRESHOLD = 0.45f
        private const val MAX_FPS = 8
        private const val FRAME_INTERVAL_MS = 1000L / MAX_FPS
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "detection-thread").also { it.isDaemon = true }
    }
    private var detector: ObjectDetector? = null
    private val running = AtomicBoolean(false)
    private var lastFrameMs = 0L

    // Cached frame dimensions for NV21→Bitmap conversion
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0

    fun start() {
        if (running.getAndSet(true)) return
        executor.submit { initDetector() }
        VideoStreamManager.addDecodedFrameListener(frameListener)
        Log.i(TAG, "Detection started")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        VideoStreamManager.removeDecodedFrameListener()
        executor.submit {
            detector?.close()
            detector = null
            Log.i(TAG, "Detection stopped")
        }
    }

    private fun initDetector() {
        try {
            val baseOptions = BaseOptions.builder()
                .setNumThreads(2)
                // GPU delegate — falls back to CPU if unavailable
                .useGpu()
                .build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(SCORE_THRESHOLD)
                .build()
            detector = ObjectDetector.createFromFileAndOptions(context, MODEL_FILE, options)
            Log.i(TAG, "EfficientDet-Lite0 loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}")
            // Retry without GPU
            try {
                val options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setMaxResults(MAX_RESULTS)
                    .setScoreThreshold(SCORE_THRESHOLD)
                    .build()
                detector = ObjectDetector.createFromFileAndOptions(context, MODEL_FILE, options)
                Log.i(TAG, "EfficientDet-Lite0 loaded (CPU fallback)")
            } catch (e2: Exception) {
                Log.e(TAG, "Model load failed entirely: ${e2.message}")
            }
        }
    }

    private val frameListener = ICameraStreamManager.CameraFrameListener {
            frame, offset, length, width, height, _ ->
        if (!running.get()) return@CameraFrameListener

        val now = System.currentTimeMillis()
        if (now - lastFrameMs < FRAME_INTERVAL_MS) return@CameraFrameListener
        lastFrameMs = now

        frameWidth = width
        frameHeight = height

        // Bounds check — MSDK occasionally delivers inconsistent offset/length
        val safeEnd = (offset + length).coerceAtMost(frame.size)
        if (safeEnd <= offset) return@CameraFrameListener
        val dataCopy = frame.copyOfRange(offset, safeEnd)

        executor.submit {
            if (!running.get() || detector == null) return@submit
            try {
                val bitmap = nv21ToBitmap(dataCopy, width, height) ?: return@submit
                val tensorImage = TensorImage.fromBitmap(bitmap)
                val rawResults = detector?.detect(tensorImage) ?: return@submit

                val results = rawResults.mapIndexed { idx, detection ->
                    val cat = detection.categories.firstOrNull()
                    val box = detection.boundingBox
                    DetectionResult(
                        label = cat?.label ?: "object",
                        confidence = cat?.score ?: 0f,
                        // Normalize box to 0-1 relative to bitmap size
                        boxNorm = RectF(
                            box.left / width,
                            box.top / height,
                            box.right / width,
                            box.bottom / height
                        ).apply {
                            left   = left.coerceIn(0f, 1f)
                            top    = top.coerceIn(0f, 1f)
                            right  = right.coerceIn(0f, 1f)
                            bottom = bottom.coerceIn(0f, 1f)
                        },
                        trackId = idx
                    )
                }
                onResults(results)
                bitmap.recycle()
            } catch (e: Exception) {
                Log.w(TAG, "Inference error: ${e.message}")
            }
        }
    }

    private fun nv21ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            val jpeg = out.toByteArray()
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } catch (e: Exception) {
            Log.w(TAG, "NV21 convert failed: ${e.message}")
            null
        }
    }
}
