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
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EfficientDet-Lite0 (COCO 80-class) on live NV21 drone frames.
 *
 * Tensor layout is detected once at load time by shape inspection.
 * Scores vs classes are resolved each inference by value range — no fragile swap state.
 * Results scan ALL N slots, not just up to count, so a bad count tensor never hides detections.
 */
class LiveObjectDetector(
    private val context: Context,
    private val onResults: (List<DetectionResult>) -> Unit
) {
    companion object {
        private const val TAG = "LiveObjectDetector"
        private const val MODEL_FILE = "efficientdet_lite0.tflite"
        private const val MAX_RESULTS = 5
        private const val SCORE_THRESHOLD = 0.05f   // low — raise once working
        private const val MAX_FPS = 5
        private const val FRAME_INTERVAL_MS = 1000L / MAX_FPS
        private const val INPUT_SIZE = 320

        private val COCO_LABELS = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush"
        )
    }

    private data class OutputLayout(
        val boxesIdx: Int,
        val flat0Idx: Int,
        val flat1Idx: Int,
        val countIdx: Int,
        val maxDetections: Int
    )

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "detection-thread").also { it.isDaemon = true }
    }
    private var interpreter: Interpreter? = null
    private var layout: OutputLayout? = null
    private var inputIsFloat = false

    private val running = AtomicBoolean(false)
    private var lastFrameMs = 0L

    @Volatile var framesReceived = 0L; private set
    @Volatile var inferencesRun = 0L; private set
    @Volatile var modelLoaded = false; private set
    @Volatile var modelLoadError: String? = null; private set
    @Volatile var debugInfo: String = ""; private set

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
            interpreter?.close()
            interpreter = null
            Log.i(TAG, "Detection stopped")
        }
    }

    private fun initDetector() {
        try {
            val afd = context.assets.openFd(MODEL_FILE)
            val model = FileInputStream(afd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            val options = Interpreter.Options().apply { numThreads = 4 }
            val interp = Interpreter(model, options)

            inputIsFloat = interp.getInputTensor(0).dataType() == DataType.FLOAT32
            val inShape = interp.getInputTensor(0).shape().toList()
            layout = detectOutputLayout(interp)
            val lay = layout!!

            val info = "in=${if (inputIsFloat) "f32" else "u8"}${inShape} " +
                "boxes=${lay.boxesIdx} f0=${lay.flat0Idx} f1=${lay.flat1Idx} " +
                "cnt=${lay.countIdx} N=${lay.maxDetections}"
            debugInfo = info
            Log.i(TAG, "Model loaded: $info")

            interpreter = interp
            modelLoaded = true
            modelLoadError = null
        } catch (e: Exception) {
            modelLoadError = e.message?.take(120) ?: "Unknown error"
            Log.e(TAG, "Model load FAILED: ${e.message}")
        }
    }

    private fun detectOutputLayout(interp: Interpreter): OutputLayout {
        val n = interp.outputTensorCount
        var boxesIdx = 0
        var countIdx = -1
        val flatCandidates = mutableListOf<Int>()
        var maxDet = 25

        for (i in 0 until n) {
            val shape = interp.getOutputTensor(i).shape()
            val dtype = interp.getOutputTensor(i).dataType()
            Log.i(TAG, "  Out[$i] shape=${shape.toList()} dtype=$dtype")
            when {
                shape.size >= 3 && shape.last() == 4 -> { boxesIdx = i; maxDet = shape[shape.size - 2] }
                shape.size == 1 -> countIdx = i
                shape.size == 2 && shape[1] == 1 -> countIdx = i  // [1,1] count
                shape.size == 2 -> flatCandidates.add(i)
            }
        }

        if (countIdx == -1) countIdx = (0 until n).firstOrNull { it != boxesIdx && it !in flatCandidates } ?: 3
        val flat0 = flatCandidates.getOrElse(0) { (boxesIdx + 1) % n }
        val flat1 = flatCandidates.getOrElse(1) { (boxesIdx + 2) % n }
        return OutputLayout(boxesIdx, flat0, flat1, countIdx, maxDet)
    }

    private val frameListener = ICameraStreamManager.CameraFrameListener {
            frame, offset, length, width, height, _ ->
        if (!running.get()) return@CameraFrameListener
        val now = System.currentTimeMillis()
        if (now - lastFrameMs < FRAME_INTERVAL_MS) return@CameraFrameListener
        lastFrameMs = now
        framesReceived++

        if (framesReceived == 1L) Log.i(TAG, "First frame: ${width}x${height} len=$length offset=$offset")

        // Guard against zero-size or out-of-bounds frames
        if (width <= 0 || height <= 0 || length <= 0) {
            debugInfo = "BAD_FRAME w=$width h=$height len=$length"
            return@CameraFrameListener
        }
        val safeEnd = (offset + length).coerceAtMost(frame.size)
        if (safeEnd <= offset) return@CameraFrameListener
        val dataCopy = frame.copyOfRange(offset, safeEnd)

        executor.submit {
            if (!running.get() || interpreter == null) return@submit
            try {
                val bitmap = nv21ToBitmap(dataCopy, width, height) ?: return@submit
                val results = runInference(bitmap, width, height)
                bitmap.recycle()
                inferencesRun++
                onResults(results)
            } catch (e: Exception) {
                Log.w(TAG, "Inference error: ${e.message}")
                debugInfo = "ERR: ${e.message?.take(80)}"
            }
        }
    }

    private fun runInference(bitmap: Bitmap, frameW: Int, frameH: Int): List<DetectionResult> {
        val interp = interpreter ?: return emptyList()
        val lay = layout ?: return emptyList()

        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        scaled.recycle()

        val bytesPerPixel = if (inputIsFloat) 4 else 1
        val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * bytesPerPixel)
            .also { it.order(ByteOrder.nativeOrder()) }

        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            if (inputIsFloat) {
                // Standard TFLite float32 EfficientDet-Lite0: normalize to [0, 1]
                inputBuffer.putFloat(r / 255.0f)
                inputBuffer.putFloat(g / 255.0f)
                inputBuffer.putFloat(b / 255.0f)
            } else {
                inputBuffer.put(r.toByte())
                inputBuffer.put(g.toByte())
                inputBuffer.put(b.toByte())
            }
        }
        inputBuffer.rewind()

        val N = lay.maxDetections
        val outBoxes = Array(1) { Array(N) { FloatArray(4) } }
        val outFlat0 = Array(1) { FloatArray(N) }
        val outFlat1 = Array(1) { FloatArray(N) }
        val outCount = FloatArray(1)

        val outputs = mutableMapOf<Int, Any>(
            lay.boxesIdx to outBoxes,
            lay.countIdx to outCount,
            lay.flat0Idx to outFlat0,
            lay.flat1Idx to outFlat1
        )
        interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        val rawCount = outCount[0]
        val count = rawCount.toInt().coerceIn(0, N)

        // Resolve scores vs classes by value range — scores are [0,1], class indices are [0,79].
        // Check max across min(count,10) slots but fall back to full N scan if count=0.
        val scanN = if (count > 0) count.coerceAtMost(10) else N
        val maxFlat0 = outFlat0[0].take(scanN).maxOrNull() ?: 0f
        val maxFlat1 = outFlat1[0].take(scanN).maxOrNull() ?: 0f

        // Whichever flat tensor has max > 1.5 is class indices; the other is scores.
        val (scores, classes) = when {
            maxFlat0 > 1.5f -> Pair(outFlat1[0], outFlat0[0])
            maxFlat1 > 1.5f -> Pair(outFlat0[0], outFlat1[0])
            else -> Pair(outFlat1[0], outFlat0[0])  // default: flat1=scores (TFHub order)
        }

        // Scan ALL N slots — don't trust count alone.
        // Models sometimes return count=0 even with valid detections due to quantization.
        val topScore = (0 until N).maxOfOrNull { scores[it] } ?: 0f
        debugInfo = "${frameW}x${frameH} cnt=%.0f top=%.2f f0=%.2f f1=%.2f".format(
            rawCount, topScore, maxFlat0, maxFlat1
        )

        val results = mutableListOf<DetectionResult>()
        for (i in 0 until N) {
            val score = scores[i]
            if (score < SCORE_THRESHOLD) continue
            if (results.size >= MAX_RESULTS) break

            val classRaw = classes[i]
            // Handle both 0-indexed (MediaPipe) and 1-indexed (TFHub) COCO labels
            val classIdx = (classRaw.toInt() - 1).coerceAtLeast(0).coerceAtMost(COCO_LABELS.size - 1)
            val box = outBoxes[0][i]

            // EfficientDet boxes: [ymin, xmin, ymax, xmax] normalized
            val ymin = box[0].coerceIn(0f, 1f)
            val xmin = box[1].coerceIn(0f, 1f)
            val ymax = box[2].coerceIn(0f, 1f)
            val xmax = box[3].coerceIn(0f, 1f)

            if (xmax <= xmin || ymax <= ymin) continue

            results.add(DetectionResult(
                label = COCO_LABELS[classIdx],
                confidence = score,
                boxNorm = RectF(xmin, ymin, xmax, ymax),
                trackId = i
            ))
        }

        if (results.isNotEmpty()) {
            Log.i(TAG, "Detected: ${results.map { "${it.label}@${"%.0f".format(it.confidence * 100)}%" }}")
        }
        return results
    }

    private fun nv21ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
        val required = width * height * 3 / 2
        if (data.size < required) {
            debugInfo = "NV21 too small: ${data.size} < $required (${width}x${height})"
            Log.w(TAG, debugInfo)
            return null
        }
        return try {
            val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream(data.size / 4)
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
            BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        } catch (e: Exception) {
            debugInfo = "NV21 convert failed: ${e.message?.take(60)}"
            Log.w(TAG, "NV21 convert: ${e.message}")
            null
        }
    }
}
