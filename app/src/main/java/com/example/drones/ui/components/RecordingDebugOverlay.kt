package com.example.drones.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.drones.recording.RecordingDebugLog

/**
 * On-screen debug overlay showing recording pipeline status.
 * Replaces the need for ADB logcat.
 */
@Composable
fun RecordingDebugOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status by RecordingDebugLog.status.collectAsState()
    val lines by RecordingDebugLog.lines.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth(0.85f)
            .heightIn(max = 300.dp)
            .background(Color.Black.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        // Header
        Row {
            Text(
                text = "REC DEBUG",
                color = Color.Cyan,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "[X]",
                color = Color.Red,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onDismiss() }
            )
        }

        // Status summary
        val statusLine = buildString {
            append("Stream: ")
            append(if (status.streamListenerActive) "ON" else "OFF")
            append(" | Frames: ${status.rawFramesReceived}")
            append(" | NAL: ${status.lastNalType}")
        }
        Text(
            text = statusLine,
            color = Color.Yellow,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )

        val csdLine = buildString {
            append("VPS:")
            append(if (status.vpsFound) "✓" else "✗")
            append(" SPS:")
            append(if (status.spsFound) "✓" else "✗")
            append(" PPS:")
            append(if (status.ppsFound) "✓" else "✗")
            append(" | Muxer:")
            append(if (status.muxerStarted) "RUNNING" else "WAITING")
            append(" (${status.muxerFrames}f)")
        }
        Text(
            text = csdLine,
            color = if (status.muxerStarted) Color.Green else Color(0xFFFF9800),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )

        if (status.fileSize > 0) {
            Text(
                text = "File: ${status.fileSize / 1024}KB",
                color = Color.White,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        status.lastError?.let { err ->
            Text(
                text = "ERR: $err",
                color = Color.Red,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Scrollable log
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
        ) {
            lines.forEach { line ->
                val color = when {
                    line.contains("ERROR") || line.contains("FAIL") -> Color.Red
                    line.contains("WARN") || line.contains("SKIP") -> Color(0xFFFF9800)
                    line.contains("found") || line.contains("started") || line.contains("Published") -> Color.Green
                    else -> Color.White.copy(alpha = 0.7f)
                }
                Text(
                    text = line,
                    color = color,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 11.sp
                )
            }
        }
    }
}
