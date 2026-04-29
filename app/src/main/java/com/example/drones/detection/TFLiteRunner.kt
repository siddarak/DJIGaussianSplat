package com.example.drones.detection

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs inference on a Bitmap using a loaded TFLite interpreter.
 * No Android camera or DJI dependencies — pure ML logic.
 *
 * COCO labels are 1-indexed in EfficientDet-Lite0 (class 1 = person).
 * We subtract 1 before looking up in COCO_LABELS (0-indexed array).
 */
object TFLiteRunner {

    private const val TAG = "TFLiteRunner"

    /**
     * Run inference on [bitmap]. Returns detections above threshold.
     * [info] must come from ModelInspector.inspect() on the same interpreter.
     */
    fun run(
        bitmap: Bitmap,
        interp: Interpreter,
        info: ModelInspector.ModelInfo
    ): Pair<List<DetectionResult>, String> {   // results + debugLine

        val size = info.inputSize
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        scaled.recycle()

        // Diagnostic: mean pixel brightness — if 0, frames are black
        var sumLum = 0L
        for (px in pixels) {
            sumLum += ((px shr 16) and 0xFF) + ((px shr 8) and 0xFF) + (px and 0xFF)
        }
        val meanLum = sumLum / (pixels.size.toLong() * 3)

        val inputBuffer = buildInputBuffer(pixels, info.inputIsFloat, size)

        // Allocate output buffers exactly sized to each tensor's total elements
        val outBoxes   = tensorBuf(interp, info.boxesIdx)
        val outClasses = tensorBuf(interp, info.classesIdx)
        val outScores  = tensorBuf(interp, info.scoresIdx)
        val outCount   = tensorBuf(interp, info.countIdx)

        val outputs = mapOf<Int, Any>(
            info.boxesIdx   to outBoxes,
            info.classesIdx to outClasses,
            info.scoresIdx  to outScores,
            info.countIdx   to outCount
        )

        try { interp.allocateTensors() } catch (_: Exception) {}
        interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        val boxesFlat  = outBoxes.floats()
        val classFlat  = outClasses.floats()
        val scoreFlat  = outScores.floats()
        val countFlat  = outCount.floats()

        val rawCount = countFlat.getOrElse(0) { 0f }
        val N = info.maxDetections
        val topScore = (0 until minOf(N, scoreFlat.size)).maxOfOrNull { scoreFlat[it] } ?: 0f
        val debugLine = "${bitmap.width}x${bitmap.height} lum=$meanLum cnt=%.0f top=%.2f s0=%.2f s1=%.2f c0=%.0f c1=%.0f b0=%.2f"
            .format(rawCount, topScore,
                scoreFlat.getOrElse(0) { 0f }, scoreFlat.getOrElse(1) { 0f },
                classFlat.getOrElse(0) { 0f }, classFlat.getOrElse(1) { 0f },
                boxesFlat.getOrElse(0) { 0f })

        val results = mutableListOf<DetectionResult>()
        for (i in 0 until minOf(N, scoreFlat.size)) {
            val score = scoreFlat[i]
            if (score < DetectorConfig.SCORE_THRESHOLD) continue
            if (results.size >= DetectorConfig.MAX_RESULTS) break

            val classRaw = classFlat.getOrElse(i) { 0f }
            val classIdx = (classRaw.toInt() - 1).coerceIn(0, DetectorConfig.LABELS.size - 1)

            val base = i * 4
            if (base + 3 >= boxesFlat.size) continue
            val ymin = boxesFlat[base].coerceIn(0f, 1f)
            val xmin = boxesFlat[base + 1].coerceIn(0f, 1f)
            val ymax = boxesFlat[base + 2].coerceIn(0f, 1f)
            val xmax = boxesFlat[base + 3].coerceIn(0f, 1f)
            if (xmax <= xmin || ymax <= ymin) continue

            results.add(DetectionResult(
                label      = DetectorConfig.LABELS[classIdx],
                confidence = score,
                boxNorm    = RectF(xmin, ymin, xmax, ymax),
                trackId    = i
            ))
        }

        if (results.isNotEmpty()) {
            Log.i(TAG, "Detected: ${results.map { "${it.label} ${"%.0f".format(it.confidence * 100)}%" }}")
        }
        return Pair(results, debugLine)
    }

    // --- helpers ---

    private fun buildInputBuffer(pixels: IntArray, isFloat: Boolean, size: Int): ByteBuffer {
        val bytesPerPixel = if (isFloat) 4 else 1
        val buf = ByteBuffer.allocateDirect(size * size * 3 * bytesPerPixel)
            .also { it.order(ByteOrder.nativeOrder()) }
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            if (isFloat) {
                buf.putFloat(r / 255.0f)
                buf.putFloat(g / 255.0f)
                buf.putFloat(b / 255.0f)
            } else {
                buf.put(r.toByte())
                buf.put(g.toByte())
                buf.put(b.toByte())
            }
        }
        buf.rewind()
        return buf
    }

    private fun tensorBuf(interp: Interpreter, idx: Int): ByteBuffer {
        val shape = interp.getOutputTensor(idx).shape()
        val n = shape.fold(1) { acc, d -> acc * d }
        return ByteBuffer.allocateDirect(n * 4).also { it.order(ByteOrder.nativeOrder()) }
    }

    private fun ByteBuffer.floats(): FloatArray {
        rewind()
        return FloatArray(capacity() / 4).also { asFloatBuffer().get(it) }
    }
}
