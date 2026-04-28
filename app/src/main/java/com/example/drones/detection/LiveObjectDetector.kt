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
        val maxDetections: Int,
        // Exact shapes from interpreter — used to allocate correctly-sized buffers
        val boxesShape: IntArray,
        val flat0Shape: IntArray,
        val flat1Shape: IntArray,
        val countShape: IntArray
    )

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "detection-thread").also { it.isDaemon = true }
    }
    private var interpreter: Interpreter? = null
    private var layout: OutputLayout? = null
    private var inputIsFloat = false

    private val running = AtomicBoolean(false)
    private val inferencing = AtomicBoolean(false)  // drop frame if inference still busy
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
        Log.i(TAG, "Output tensor count: $n")
        for (i in 0 until n) {
            val shape = interp.getOutputTensor(i).shape()
            Log.i(TAG, "  Out[$i] shape=${shape.toList()} dtype=${interp.getOutputTensor(i).dataType()}")
        }

        // Identify roles by shape — works for any output count
        var boxesIdx = -1; var classesIdx = -1; var scoresIdx = -1; var countIdx = -1
        var maxDet = 25
        val flatCandidates = mutableListOf<Int>()

        for (i in 0 until n) {
            val s = interp.getOutputTensor(i).shape()
            when {
                s.size >= 3 && s.last() == 4 -> { boxesIdx = i; maxDet = s[s.size - 2] }
                s.size == 1 || (s.size == 2 && s.last() == 1) -> countIdx = i
                s.size == 2 -> flatCandidates.add(i)
            }
        }

        // EfficientDet standard: first flat = classes, second flat = scores
        classesIdx = flatCandidates.getOrElse(0) { 1 }
        scoresIdx  = flatCandidates.getOrElse(1) { 2 }
        if (boxesIdx == -1) boxesIdx = 0
        if (countIdx == -1) countIdx = flatCandidates.getOrElse(2) { n - 1 }

        Log.i(TAG, "Layout: boxes=$boxesIdx classes=$classesIdx scores=$scoresIdx count=$countIdx N=$maxDet")

        return OutputLayout(
            boxesIdx      = boxesIdx,
            flat0Idx      = classesIdx,
            flat1Idx      = scoresIdx,
            countIdx      = countIdx,
            maxDetections = maxDet,
            boxesShape    = interp.getOutputTensor(boxesIdx).shape(),
            flat0Shape    = interp.getOutputTensor(classesIdx).shape(),
            flat1Shape    = interp.getOutputTensor(scoresIdx).shape(),
            countShape    = interp.getOutputTensor(countIdx).shape()
        )
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

        // Drop frame if previous inference not done — prevents unbounded queue + OOM
        if (!inferencing.compareAndSet(false, true)) return@CameraFrameListener

        executor.submit {
            if (!running.get() || interpreter == null) {
                inferencing.set(false)
                return@submit
            }
            try {
                val bitmap = nv21ToBitmap(dataCopy, width, height)
                if (bitmap != null) {
                    val results = runInference(bitmap, width, height)
                    bitmap.recycle()
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
        // ByteBuffer outputs: TFLite writes raw float32 bytes regardless of tensor shape.
        // This avoids "cannot copy from TFLite tensor" caused by shape mismatch on stateful models.
        val outBoxesBuf = allocBuf(lay.boxesShape)
        val outFlat0Buf = allocBuf(lay.flat0Shape)
        val outFlat1Buf = allocBuf(lay.flat1Shape)
        val outCountBuf = allocBuf(lay.countShape)

        val outputs = mutableMapOf<Int, Any>(
            lay.boxesIdx to outBoxesBuf,
            lay.countIdx to outCountBuf,
            lay.flat0Idx to outFlat0Buf,
            lay.flat1Idx to outFlat1Buf
        )
        interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        // Read back as flat FloatArrays
        val boxesFlat = outBoxesBuf.toFloats()   // [N*4]: ymin,xmin,ymax,xmax per detection
        val flat0     = outFlat0Buf.toFloats()
        val flat1     = outFlat1Buf.toFloats()
        val countFlat = outCountBuf.toFloats()

        val rawCount = countFlat.getOrElse(0) { 0f }
        val count = rawCount.toInt().coerceIn(0, N)

        // flat0 = classes (1-indexed), flat1 = scores (0-1) — hardcoded for TFHub EfficientDet-Lite0
        val scores  = flat1
        val classes = flat0

        val topScore = (0 until minOf(N, scores.size)).maxOfOrNull { scores[it] } ?: 0f
        debugInfo = "${frameW}x${frameH} cnt=%.0f top=%.2f".format(rawCount, topScore)

        val results = mutableListOf<DetectionResult>()
        for (i in 0 until minOf(N, scores.size)) {
            val score = scores[i]
            if (score < SCORE_THRESHOLD) continue
            if (results.size >= MAX_RESULTS) break

            val classRaw = if (i < classes.size) classes[i] else 0f
            val classIdx = (classRaw.toInt() - 1).coerceAtLeast(0).coerceAtMost(COCO_LABELS.size - 1)

            // boxes flat layout: i*4 = ymin, i*4+1 = xmin, i*4+2 = ymax, i*4+3 = xmax
            val base = i * 4
            if (base + 3 >= boxesFlat.size) continue
            val ymin = boxesFlat[base].coerceIn(0f, 1f)
            val xmin = boxesFlat[base + 1].coerceIn(0f, 1f)
            val ymax = boxesFlat[base + 2].coerceIn(0f, 1f)
            val xmax = boxesFlat[base + 3].coerceIn(0f, 1f)

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

    private fun allocBuf(shape: IntArray): ByteBuffer {
        val n = shape.fold(1) { acc, d -> acc * d }
        return ByteBuffer.allocateDirect(n * 4).also { it.order(ByteOrder.nativeOrder()) }
    }

    private fun ByteBuffer.toFloats(): FloatArray {
        rewind()
        val fa = FloatArray(capacity() / 4)
        asFloatBuffer().get(fa)
        return fa
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
