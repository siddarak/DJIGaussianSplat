package com.example.drones.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Recording control panel — positioned on the right side of the flight screen.
 *
 * Shows:
 * - A big record/stop button (red circle / red square)
 * - Recording timer
 * - Indicators for on-device and drone recording status
 */
@Composable
fun RecordingControls(
    isRecordingOnDevice: Boolean,
    isRecordingOnDrone: Boolean,
    recordingTimeSeconds: Int,
    isProductConnected: Boolean,
    lastRecordingPath: String,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onOpenLastRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isAnyRecording = isRecordingOnDevice || isRecordingOnDrone

    Column(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Record button
        RecordButton(
            isRecording = isAnyRecording,
            enabled = isProductConnected,
            onClick = {
                if (isAnyRecording) onStopRecording() else onStartRecording()
            }
        )

        // Timer (only when recording)
        if (isAnyRecording) {
            RecordingTimer(seconds = recordingTimeSeconds)
        }

        // Status indicators
        if (isAnyRecording) {
            RecordingStatusIndicators(
                onDevice = isRecordingOnDevice,
                onDrone = isRecordingOnDrone
            )
        }

        // Last recording — clickable to open
        if (!isAnyRecording && lastRecordingPath.isNotEmpty()) {
            LastRecordingChip(
                filePath = lastRecordingPath,
                onClick = onOpenLastRecording
            )
        }
    }
}

@Composable
private fun RecordButton(
    isRecording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isRecording) Color.Red else Color.White,
        label = "border"
    )

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .border(3.dp, borderColor, CircleShape)
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (isRecording) {
            // Pulsing red dot when recording
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )
            // Stop icon (rounded square)
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .alpha(alpha)
                    .background(Color.Red, RoundedCornerShape(4.dp))
            )
        } else {
            // Record icon (red circle)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        if (enabled) Color.Red else Color.Gray,
                        CircleShape
                    )
            )
        }
    }
}

@Composable
private fun RecordingTimer(seconds: Int) {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    val timeStr = if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }

    // Pulsing red dot + time
    val infiniteTransition = rememberInfiniteTransition(label = "timerPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(dotAlpha)
                .background(Color.Red, CircleShape)
        )
        Text(
            text = timeStr,
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun RecordingStatusIndicators(
    onDevice: Boolean,
    onDrone: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        StatusDot(label = "Phone", active = onDevice)
        StatusDot(label = "SD", active = onDrone)
    }
}

@Composable
private fun StatusDot(label: String, active: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    if (active) Color.Green else Color.Gray,
                    CircleShape
                )
        )
        Text(
            text = label,
            color = if (active) Color.White else Color.Gray,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun LastRecordingChip(
    filePath: String,
    onClick: () -> Unit
) {
    val fileName = filePath.substringAfterLast("/")

    Column(
        modifier = Modifier
            .background(Color(0xFF1B5E20).copy(alpha = 0.7f), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "LAST REC",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = fileName,
            color = Color.White,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = "Tap to open",
            color = Color(0xFF81C784),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
