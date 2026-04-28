package com.example.drones.detection

import android.content.Context
import android.util.Log
import com.example.drones.sdk.VideoStreamManager
import dji.v5.manager.interfaces.ICameraStreamManager
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wires the DJI NV21 frame stream to TFLiteRunner.
 * All ML logic is in TFLiteRunner. All conversion logic is in FrameProcessor.
 * This class only handles threading, throttling, and DJI lifecycle.
 */
class LiveObjectDetector(
    private val context: Context,
    private val onResults: (List<DetectionResult>) -> Unit
) {
    companion object {
        private const val TAG = "LiveObjectDetector"
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
        private const val MAX_FPS = 5
        private const val FRAME_INTERVAL_MS = 1000L / MAX_FPS
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "detection-thread").also { it.isDaemon = true }
    }

    private var interpreter: Interpreter? = null
    private var modelInfo: ModelInspector.ModelInfo? = null

    private val running    = AtomicBoolean(false)
    private val inferencing = AtomicBoolean(false)
    private var lastFrameMs = 0L

    @Volatile var framesReceived = 0L;      private set
    @Volatile var inferencesRun  = 0L;      private set
    @Volatile var modelLoaded    = false;   private set
    @Volatile var modelLoadError: String? = null; private set
    @Volatile var debugInfo: String = "";   private set

    // --- lifecycle ---

    fun start() {
        if (running.getAndSet(true)) return
        executor.submit { loadModel() }
        VideoStreamManager.addDecodedFrameListener(frameListener)
        Log.i(TAG, "Detection started")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        VideoStreamManager.removeDecodedFrameListener()
        executor.submit {
            interpreter?.close()
            interpreter = null
            modelInfo = null
            Log.i(TAG, "Detection stopped")
        }
    }

    // --- model loading ---

    private fun loadModel() {
        try {
            val afd = context.assets.openFd(MODEL_FILE)
            val mapped = FileInputStream(afd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            val interp = Interpreter(mapped, Interpreter.Options().apply { numThreads = 4 })

            val info = ModelInspector.inspect(interp)
            debugInfo = info.summary

            interpreter = interp
            modelInfo   = info
            modelLoaded = true
            modelLoadError = null
            Log.i(TAG, "Model ready: ${info.summary}")
        } catch (e: Exception) {
            modelLoadError = e.message?.take(120) ?: "Unknown error"
            Log.e(TAG, "Model load FAILED: ${e.message}")
        }
    }

    // --- frame listener ---

    private val frameListener = ICameraStreamManager.CameraFrameListener {
            frame, offset, length, width, height, _ ->

        if (!running.get()) return@CameraFrameListener

        val now = System.currentTimeMillis()
        if (now - lastFrameMs < FRAME_INTERVAL_MS) return@CameraFrameListener
        lastFrameMs = now
        framesReceived++

        if (width <= 0 || height <= 0 || length <= 0) return@CameraFrameListener

        val safeEnd = (offset + length).coerceAtMost(frame.size)
        if (safeEnd <= offset) return@CameraFrameListener
        val dataCopy = frame.copyOfRange(offset, safeEnd)

        // Drop if already inferencing — prevents queue buildup and OOM
        if (!inferencing.compareAndSet(false, true)) return@CameraFrameListener

        executor.submit {
            val interp = interpreter
            val info   = modelInfo
            if (!running.get() || interp == null || info == null) {
                inferencing.set(false)
                return@submit
            }
            try {
                val bitmap = FrameProcessor.nv21ToBitmap(dataCopy, width, height)
                if (bitmap != null) {
                    val (results, debug) = TFLiteRunner.run(bitmap, interp, info)
                    bitmap.recycle()
                    debugInfo = debug
                    inferencesRun++
                    onResults(results)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Inference error: ${e.message}")
                debugInfo = "ERR: ${e.message?.take(80)}"
            } finally {
                inferencing.set(false)
            }
        }
    }
}
