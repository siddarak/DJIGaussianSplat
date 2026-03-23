package com.example.drones.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Muxes raw H.264/H.265 NAL units into an MP4 file.
 *
 * Thread safety: all public methods synchronize on [muxerLock].
 * The MSDK stream callback fires on a background thread — every entry point here
 * must be safe to call from any thread.
 *
 * Timestamp handling:
 * Timestamps come from System.nanoTime() converted to microseconds.
 * We store the first frame's timestamp as t=0 so the MP4 starts at 0s.
 * All subsequent frames are relative to that first timestamp.
 */
class Mp4Muxer(
    private val outputFile: File,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val frameRate: Int = 30
) {
    companion object {
        private const val TAG = "Mp4Muxer"
        private const val DEFAULT_BITRATE = 10_000_000 // 10 Mbps
    }

    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var startTimeUs = -1L
    private var frameCount = 0L
    private val muxerLock = Any()

    /**
     * Configure codec parameters and start the muxer.
     * Must be called once with SPS/PPS before any frames are written.
     * Idempotent — safe to call multiple times (only acts on first call).
     */
    fun configureSpsAndPps(spsData: ByteArray, ppsData: ByteArray, isH265: Boolean = false) {
        synchronized(muxerLock) {
            if (muxerStarted) return

            var newMuxer: MediaMuxer? = null
            try {
                val mime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC
                           else MediaFormat.MIMETYPE_VIDEO_AVC

                val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(spsData))
                    setByteBuffer("csd-1", ByteBuffer.wrap(ppsData))
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BITRATE)
                }

                newMuxer = MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                videoTrackIndex = newMuxer.addTrack(format)
                newMuxer.start()

                // Only assign to field after fully started — prevents partial-init visibility
                muxer = newMuxer
                muxerStarted = true
                Log.i(TAG, "Muxer started: ${outputFile.name} " +
                        "${width}x${height}@${frameRate}fps ${if (isH265) "H.265" else "H.264"}")
            } catch (e: Exception) {
                // Release the local reference if we failed mid-init
                try { newMuxer?.release() } catch (_: Exception) {}
                muxer = null
                muxerStarted = false
                videoTrackIndex = -1
                Log.e(TAG, "Failed to configure muxer: ${e.message}")
            }
        }
    }

    /**
     * Write a single NAL unit to the MP4.
     * No-op if muxer hasn't been configured yet.
     */
    fun writeNalUnit(
        data: ByteArray,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
        isKeyFrame: Boolean
    ) {
        synchronized(muxerLock) {
            val activeMuxer = muxer ?: return
            if (!muxerStarted) return

            try {
                if (startTimeUs < 0) {
                    startTimeUs = presentationTimeUs
                }
                // Clamp to 0 — handles any clock jitter on first frames
                val adjustedTime = maxOf(0L, presentationTimeUs - startTimeUs)

                val buffer = ByteBuffer.wrap(data, offset, length)
                val bufferInfo = MediaCodec.BufferInfo().apply {
                    set(offset, length, adjustedTime,
                        if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                }

                activeMuxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                frameCount++

                if (frameCount % 300 == 0L) {
                    Log.d(TAG, "Frames: $frameCount | Time: ${adjustedTime / 1_000_000}s")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame write failed: ${e.message}")
            }
        }
    }

    /** Finalize and close the MP4. No frames can be written after this. */
    fun stop() {
        synchronized(muxerLock) {
            val activeMuxer = muxer ?: return
            try {
                if (muxerStarted) activeMuxer.stop()
                activeMuxer.release()
                Log.i(TAG, "Muxer stopped. Frames: $frameCount, File: ${outputFile.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping muxer: ${e.message}")
            } finally {
                muxer = null
                muxerStarted = false
                videoTrackIndex = -1
            }
        }
    }

    // Read outside lock intentionally — only informational
    val isStarted: Boolean get() = muxerStarted
    val totalFrames: Long get() = frameCount
}
