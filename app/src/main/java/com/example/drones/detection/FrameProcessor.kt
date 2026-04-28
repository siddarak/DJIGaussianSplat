package com.example.drones.detection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Converts raw NV21 camera frames to Bitmap.
 * Isolated here so the conversion logic can be changed without touching inference.
 */
object FrameProcessor {

    private const val TAG = "FrameProcessor"

    /**
     * Returns null (and logs reason) if conversion fails.
     * Caller must call bitmap.recycle() when done.
     */
    fun nv21ToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Bad dimensions: ${width}x${height}")
            return null
        }
        val required = width * height * 3 / 2
        if (data.size < required) {
            Log.w(TAG, "Buffer too small: ${data.size} < $required for ${width}x${height}")
            return null
        }
        return try {
            val yuv = YuvImage(data, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream(data.size / 4)
            yuv.compressToJpeg(Rect(0, 0, width, height), 80, out)
            BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        } catch (e: Exception) {
            Log.w(TAG, "NV21 convert failed: ${e.message}")
            null
        }
    }
}
