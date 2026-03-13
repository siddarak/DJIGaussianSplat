package com.example.drones

import android.util.Log
import dji.v5.manager.datacenter.MediaDataCenter

class VideoCapture(private val muxer: Mp4Muxer) {
    private val videoStreamManager = MediaDataCenter.getInstance().videoStreamManager
    private var frameCount = 0

    fun start() {
        val channels = videoStreamManager.availableVideoChannels
        if (channels.isNullOrEmpty()) {
            Log.e("VideoCapture", "No video channels available")
            return
        }

        Log.d("VideoCapture", "Video channels acquired: ${channels.size}")
        // TODO: Add video frame listener when API is clarified
    }

    fun stop() {
        Log.d("VideoCapture", "Video capture stopped, total frames: $frameCount")
    }
}
