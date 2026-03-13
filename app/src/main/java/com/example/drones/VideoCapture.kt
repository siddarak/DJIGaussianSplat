package com.example.drones

import android.util.Log
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.IVideoStreamDataListener
import dji.v5.common.videostream.VideoFrameBuffer

class VideoCapture(private val muxer: Mp4Muxer) {
    private val videoStreamManager = MediaDataCenter.getInstance().videoStreamManager
    private var frameCount = 0
    private var spsFound = false
    private var ppsFound = false

    private val listener = IVideoStreamDataListener { videoBuffer: VideoFrameBuffer ->
        processH264Frame(videoBuffer)
    }

    fun start() {
        val channels = videoStreamManager.availableVideoChannels
        if (channels.isEmpty()) {
            Log.e("VideoCapture", "No video channels available")
            return
        }

        val channel = channels[0]
        channel.addVideoStreamDataListener(listener)
        Log.d("VideoCapture", "Video stream listener attached to channel 0")
    }

    fun stop() {
        val channels = videoStreamManager.availableVideoChannels
        channels.forEach { it.removeVideoStreamDataListener(listener) }
        Log.d("VideoCapture", "Video stream listener removed, total frames: $frameCount")
    }

    /**
     * Parse raw H264 data and extract SPS/PPS for muxer configuration.
     * H264 NAL units start with 0x00 0x00 0x00 0x01 or 0x00 0x00 0x01 (start code)
     * NAL unit type is in the lower 5 bits of the first byte after start code:
     *   Type 7 = SPS (Sequence Parameter Set)
     *   Type 8 = PPS (Picture Parameter Set)
     */
    private fun processH264Frame(buffer: VideoFrameBuffer) {
        frameCount++
        val data = buffer.data
        val length = buffer.length

        var i = 0
        while (i < length - 4) {
            // Look for start code (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
            val isStartCode4 = i <= length - 5 &&
                data[i] == 0x00.toByte() &&
                data[i + 1] == 0x00.toByte() &&
                data[i + 2] == 0x00.toByte() &&
                data[i + 3] == 0x01.toByte()

            val isStartCode3 = i <= length - 4 &&
                data[i] == 0x00.toByte() &&
                data[i + 1] == 0x00.toByte() &&
                data[i + 2] == 0x01.toByte()

            when {
                isStartCode4 -> {
                    val nalUnitByte = data[i + 4]
                    val nalType = (nalUnitByte.toInt() and 0x1F)

                    when (nalType) {
                        7 -> { // SPS
                            extractAndStoreSps(data, i + 4, length)
                            spsFound = true
                        }
                        8 -> { // PPS
                            extractAndStorePps(data, i + 4, length)
                            ppsFound = true
                            if (spsFound && ppsFound && !muxer.isStarted()) {
                                muxer.configureSpsAndPps(findSpsData(data)!!, findPpsData(data)!!)
                            }
                        }
                        5 -> { // IDR (keyframe)
                            if (muxer.isStarted()) {
                                muxer.writeNalUnit(data, i, length - i,
                                    System.currentTimeMillis() * 1000, isKeyFrame = true)
                            }
                        }
                        1 -> { // Non-IDR slice
                            if (muxer.isStarted()) {
                                muxer.writeNalUnit(data, i, length - i,
                                    System.currentTimeMillis() * 1000, isKeyFrame = false)
                            }
                        }
                    }
                    i += 4
                }
                isStartCode3 -> {
                    i += 3
                }
                else -> {
                    i++
                }
            }
        }

        if (frameCount % 100 == 0) {
            Log.d("VideoCapture", "Processed $frameCount frames (SPS: $spsFound, PPS: $ppsFound)")
        }
    }

    private fun extractAndStoreSps(data: ByteArray, offset: Int, length: Int) {
        // SPS continues until next NAL unit or end of buffer
        spsFound = true
        Log.d("VideoCapture", "SPS found at offset $offset")
    }

    private fun extractAndStorePps(data: ByteArray, offset: Int, length: Int) {
        ppsFound = true
        Log.d("VideoCapture", "PPS found at offset $offset")
    }

    private fun findSpsData(data: ByteArray): ByteArray? {
        // Simplified: return dummy SPS for now
        // In production, properly parse and extract the actual SPS NAL unit
        return ByteArray(30) // Placeholder
    }

    private fun findPpsData(data: ByteArray): ByteArray? {
        // Simplified: return dummy PPS for now
        return ByteArray(10) // Placeholder
    }
}
