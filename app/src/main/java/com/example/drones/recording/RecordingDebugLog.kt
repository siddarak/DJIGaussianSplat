package com.example.drones.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-app debug log for recording pipeline.
 * Shows on-screen so we don't need ADB.
 * Max 30 lines — oldest scroll off.
 */
object RecordingDebugLog {

    private const val MAX_LINES = 30

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    // Summary counters shown in compact mode
    private val _status = MutableStateFlow(RecordingDebugStatus())
    val status: StateFlow<RecordingDebugStatus> = _status.asStateFlow()

    fun log(msg: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date())
        val line = "[$timestamp] $msg"
        _lines.update { current ->
            (current + line).takeLast(MAX_LINES)
        }
        android.util.Log.d("REC_DEBUG", msg)
    }

    fun updateStatus(block: RecordingDebugStatus.() -> RecordingDebugStatus) {
        _status.update { it.block() }
    }

    fun clear() {
        _lines.update { emptyList() }
        _status.update { RecordingDebugStatus() }
    }
}

data class RecordingDebugStatus(
    val streamListenerActive: Boolean = false,
    val rawFramesReceived: Long = 0,
    val vpsFound: Boolean = false,
    val spsFound: Boolean = false,
    val ppsFound: Boolean = false,
    val muxerStarted: Boolean = false,
    val muxerFrames: Long = 0,
    val lastNalType: Int = -1,
    val lastError: String? = null,
    val fileSize: Long = 0
)
