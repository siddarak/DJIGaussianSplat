package com.example.drones.detection

import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * Inspects a loaded TFLite interpreter and returns a ModelInfo describing
 * tensor layout. Never throws — worst case returns a best-effort layout
 * with a diagnostic message in [ModelInfo.summary].
 */
object ModelInspector {

    private const val TAG = "ModelInspector"

    data class ModelInfo(
        val inputIsFloat: Boolean,
        val inputSize: Int,           // assumes square input, e.g. 320
        val outputCount: Int,
        val boxesIdx: Int,            // [1, N, 4] — ymin xmin ymax xmax
        val classesIdx: Int,          // [1, N]    — float class indices (1-indexed COCO)
        val scoresIdx: Int,           // [1, N]    — float confidence 0-1
        val countIdx: Int,            // [1]       — num valid detections
        val maxDetections: Int,
        val outputShapes: List<List<Int>>,
        val summary: String
    )

    fun inspect(interp: Interpreter): ModelInfo {
        val n = interp.outputTensorCount
        val shapes = mutableListOf<List<Int>>()
        val log = StringBuilder("n=$n")

        for (i in 0 until n) {
            val s = try {
                interp.getOutputTensor(i).shape().toList()
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read tensor $i: ${e.message}")
                listOf(-1)
            }
            shapes.add(s)
            log.append(" [$i]${s}")
        }
        Log.i(TAG, "Outputs: $log")

        // Identify roles by shape
        var boxesIdx = -1
        var countIdx = -1
        val flatIdxs = mutableListOf<Int>()
        var maxDet = 25

        for (i in shapes.indices) {
            val s = shapes[i]
            when {
                s.size >= 3 && s.last() == 4 -> {
                    boxesIdx = i
                    maxDet = s[s.size - 2]
                }
                s.size == 1 -> countIdx = i
                s.size == 2 && s.last() == 1 -> countIdx = i
                s.size == 2 -> flatIdxs.add(i)
            }
        }

        // Clamp all indices to valid range — never access out-of-bounds
        val safeBoxes   = (if (boxesIdx   >= 0) boxesIdx   else 0).coerceIn(0, n - 1)
        val safeClasses = (flatIdxs.getOrElse(0) { 1 }).coerceIn(0, n - 1)
        val safeScores  = (flatIdxs.getOrElse(1) { 2 }).coerceIn(0, n - 1)
        val safeCount   = (if (countIdx   >= 0) countIdx   else (n - 1)).coerceIn(0, n - 1)

        val inputTensor = interp.getInputTensor(0)
        val inputIsFloat = inputTensor.dataType() == DataType.FLOAT32
        val inputShape = inputTensor.shape().toList()
        val inputSize = inputShape.getOrElse(1) { 320 }

        val summary = "n=$n boxes=$safeBoxes cls=$safeClasses scr=$safeScores cnt=$safeCount N=$maxDet"
        Log.i(TAG, "Layout: $summary")

        return ModelInfo(
            inputIsFloat   = inputIsFloat,
            inputSize      = inputSize,
            outputCount    = n,
            boxesIdx       = safeBoxes,
            classesIdx     = safeClasses,
            scoresIdx      = safeScores,
            countIdx       = safeCount,
            maxDetections  = maxDet,
            outputShapes   = shapes,
            summary        = summary
        )
    }
}
