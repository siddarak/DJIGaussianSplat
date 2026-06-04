package com.example.drones.sdk

import android.util.Log
import com.example.drones.util.FileLogger
import dji.sdk.keyvalue.key.DJICameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.VideoFrameRate
import dji.sdk.keyvalue.value.camera.VideoResolution
import dji.sdk.keyvalue.value.camera.VideoResolutionFrameRate
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

/**
 * Sets the drone camera's recording resolution + frame rate.
 *
 * This controls what the drone records to its **SD card** (the full-quality
 * file used for reconstruction). The on-device muxed file is the liveview
 * transmission stream (~1080p) and is not affected by this — the air link
 * never carries 4K.
 *
 * Target: 4K 3840x2160 @ 60 fps. Falls back to 4K/30 if 60 is unavailable,
 * after checking the model's supported range.
 */
object CameraController {

    private const val TAG = "CameraController"

    private val TARGET_RESOLUTION = VideoResolution.RESOLUTION_3840x2160
    private val PREFERRED_RATE = VideoFrameRate.RATE_60FPS
    private val FALLBACK_RATE = VideoFrameRate.RATE_30FPS

    /** Apply 4K/60 (or 4K/30 fallback) to the SD recording. Safe to call on connect. */
    fun applyBestVideoMode() {
        val km = KeyManager.getInstance()
        val rangeKey = KeyTools.createKey(DJICameraKey.KeyVideoResolutionFrameRateRange)

        // Query supported modes, pick 4K/60 if present else 4K/30 else leave as-is.
        try {
            val supported: List<VideoResolutionFrameRate>? = km.getValue(rangeKey)
            val want4k60 = supported?.any {
                it.resolution == TARGET_RESOLUTION && it.frameRate == PREFERRED_RATE
            } ?: true   // if range unknown, attempt 60 anyway
            val rate = if (want4k60) PREFERRED_RATE else FALLBACK_RATE
            setMode(TARGET_RESOLUTION, rate)
        } catch (e: Exception) {
            FileLogger.write("Camera mode range query failed: ${e.message} — trying 4K/60 blind")
            setMode(TARGET_RESOLUTION, PREFERRED_RATE)
        }
    }

    private fun setMode(res: VideoResolution, rate: VideoFrameRate) {
        val km = KeyManager.getInstance()
        val key = KeyTools.createKey(DJICameraKey.KeyVideoResolutionFrameRate)
        val value = VideoResolutionFrameRate(res, rate)
        km.setValue(key, value, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                FileLogger.write("Camera video mode set: $res @ $rate")
                Log.i(TAG, "Video mode set: $res @ $rate")
            }
            override fun onFailure(error: IDJIError) {
                FileLogger.write("Camera video mode set FAILED ($res @ $rate): ${error.description()}")
                // If 60 failed and we tried it, drop to 30 once.
                if (rate == PREFERRED_RATE) {
                    Log.w(TAG, "60fps rejected, falling back to 30fps")
                    setMode(res, FALLBACK_RATE)
                }
            }
        })
    }
}
