package com.example.drones

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

class Mp4Muxer(outputFile: File, private val width: Int = 1920, private val height: Int = 1080) {
    private val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var trackIndex = -1
    private var muxerStarted = false
    private var startTimeUs = -1L
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null

    init {
        Log.d("Mp4Muxer", "Muxer created for ${outputFile.name}")
    }

    /**
     * Extract SPS/PPS from H264 stream and configure muxer.
     * Called when first IDR (keyframe) is encountered.
     */
    fun configureSpsAndPps(spsData: ByteArray, ppsData: ByteArray) {
        if (muxerStarted) {
            Log.w("Mp4Muxer", "Muxer already started, ignoring SPS/PPS config")
            return
        }

        this.spsData = spsData
        this.ppsData = ppsData

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(spsData))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsData))
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000) // 10 Mbps

        trackIndex = muxer.addTrack(format)
        muxer.start()
        muxerStarted = true
        Log.d("Mp4Muxer", "Muxer started, track index: $trackIndex")
    }

    /**
     * Write a single H264 NAL unit to the MP4 file.
     * Presentation time must be monotonically increasing (in microseconds).
     */
    fun writeNalUnit(data: ByteArray, offset: Int, length: Int, presentationTimeUs: Long, isKeyFrame: Boolean) {
        if (!muxerStarted) {
            Log.w("Mp4Muxer", "Muxer not started yet, discarding NAL unit")
            return
        }

        if (startTimeUs < 0) {
            startTimeUs = presentationTimeUs
        }

        val adjustedTime = presentationTimeUs - startTimeUs
        val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0

        val info = MediaCodec.BufferInfo().apply {
            set(offset, length, adjustedTime, flags)
        }

        muxer.writeSampleData(trackIndex, ByteBuffer.wrap(data, offset, length), info)
    }

    fun stop() {
        if (muxerStarted) {
            muxer.stop()
            muxer.release()
            muxerStarted = false
            Log.d("Mp4Muxer", "Muxer stopped and released")
        }
    }

    fun isStarted(): Boolean = muxerStarted
}
