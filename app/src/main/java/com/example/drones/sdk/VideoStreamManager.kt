package com.example.drones.sdk

import android.util.Log
import android.view.Surface
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.sdk.keyvalue.value.common.ComponentIndexType

object VideoStreamManager {

    private const val TAG = "VideoStreamManager"

    private val cameraStreamManager: ICameraStreamManager
        get() = MediaDataCenter.getInstance().cameraStreamManager

    // Lock guards surface and listener references accessed from multiple threads
    private val lock = Any()
    private var currentSurface: Surface? = null
    private var receiveStreamListener: ICameraStreamManager.ReceiveStreamListener? = null
    private var frameListener: ICameraStreamManager.CameraFrameListener? = null

    /**
     * Attach a Surface to receive decoded video frames for display.
     * If a surface is already attached, it is cleanly removed first.
     */
    fun startVideoFeed(surface: Surface, width: Int, height: Int) {
        synchronized(lock) {
            // Remove existing surface before attaching new one — prevents leak
            currentSurface?.let { old ->
                try {
                    cameraStreamManager.removeCameraStreamSurface(old)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove old surface: ${e.message}")
                }
            }

            try {
                cameraStreamManager.setKeepAliveDecoding(true)
                cameraStreamManager.putCameraStreamSurface(
                    ComponentIndexType.LEFT_OR_MAIN,
                    surface,
                    width,
                    height,
                    ICameraStreamManager.ScaleType.CENTER_INSIDE
                )
                currentSurface = surface
                Log.i(TAG, "Video feed started: ${width}x${height}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start video feed: ${e.message}")
                currentSurface = null
            }
        }
    }

    /**
     * Detach the current display surface and stop video rendering.
     */
    fun stopVideoFeed() {
        synchronized(lock) {
            val surface = currentSurface ?: return
            try {
                cameraStreamManager.removeCameraStreamSurface(surface)
                Log.i(TAG, "Video feed stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop video feed: ${e.message}")
            } finally {
                // Always clear reference even if removal throws
                currentSurface = null
            }
        }
    }

    /**
     * Subscribe to raw encoded stream data (H.264/H.265 bytes).
     * Used by RecordingManager to capture frames for on-device MP4 muxing.
     */
    fun addRawStreamListener(listener: ICameraStreamManager.ReceiveStreamListener) {
        synchronized(lock) {
            // Remove previous listener before adding new one
            receiveStreamListener?.let { old ->
                try { cameraStreamManager.removeReceiveStreamListener(old) } catch (_: Exception) {}
            }
            try {
                // Ensure decoder is alive — raw stream only flows when decoding is active
                cameraStreamManager.setKeepAliveDecoding(true)

                cameraStreamManager.addReceiveStreamListener(
                    ComponentIndexType.LEFT_OR_MAIN,
                    listener
                )
                receiveStreamListener = listener
                Log.i(TAG, "Raw stream listener added")
                com.example.drones.recording.RecordingDebugLog.log("VideoStreamManager: listener added OK")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add raw stream listener: ${e.message}")
                com.example.drones.recording.RecordingDebugLog.log("VideoStreamManager: FAILED to add listener: ${e.message}")
            }
        }
    }

    /**
     * Unsubscribe the raw stream listener.
     */
    fun removeRawStreamListener() {
        synchronized(lock) {
            val listener = receiveStreamListener ?: return
            try {
                cameraStreamManager.removeReceiveStreamListener(listener)
                Log.i(TAG, "Raw stream listener removed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove raw stream listener: ${e.message}")
            } finally {
                receiveStreamListener = null
            }
        }
    }

    /**
     * Subscribe to decoded YUV frames (NV21).
     * Used for future on-device object detection.
     */
    fun addDecodedFrameListener(listener: ICameraStreamManager.CameraFrameListener) {
        synchronized(lock) {
            frameListener?.let { old ->
                try { cameraStreamManager.removeFrameListener(old) } catch (_: Exception) {}
            }
            try {
                cameraStreamManager.addFrameListener(
                    ComponentIndexType.LEFT_OR_MAIN,
                    ICameraStreamManager.FrameFormat.NV21,
                    listener
                )
                frameListener = listener
                Log.i(TAG, "Decoded frame listener added (NV21)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add frame listener: ${e.message}")
            }
        }
    }

    fun removeDecodedFrameListener() {
        synchronized(lock) {
            val listener = frameListener ?: return
            try {
                cameraStreamManager.removeFrameListener(listener)
                Log.i(TAG, "Decoded frame listener removed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove frame listener: ${e.message}")
            } finally {
                frameListener = null
            }
        }
    }

    /** Remove all listeners and surfaces. Call on app destroy. */
    fun cleanup() {
        stopVideoFeed()
        removeRawStreamListener()
        removeDecodedFrameListener()
    }
}
