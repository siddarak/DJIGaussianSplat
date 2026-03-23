package com.example.drones.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.drones.ui.MainViewModel
import com.example.drones.ui.components.BottomTelemetryBar
import com.example.drones.ui.components.LeftTelemetryPanel
import com.example.drones.ui.components.ObjectSelector
import com.example.drones.ui.components.RecordingControls
import com.example.drones.ui.components.RightTelemetryPanel
import com.example.drones.ui.components.TopHudBar
import com.example.drones.ui.components.VideoFeedView
import com.example.drones.ui.components.WarningBanners
import java.io.File

@Composable
fun FlightScreen(viewModel: MainViewModel) {
    val state by viewModel.droneState.collectAsState()
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Layer 0: Video feed (full screen background)
        VideoFeedView(
            modifier = Modifier.fillMaxSize(),
            isProductConnected = state.productConnected
        )

        // Layer 1: Object selection overlay (sits on top of video, below HUD)
        ObjectSelector(
            selectedRegion = state.selectedRegion,
            isSelectionMode = state.isSelectionMode,
            onRegionSelected = { region -> viewModel.selectRegion(region) },
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2: HUD overlays
        Column(modifier = Modifier.fillMaxSize()) {

            // Top bar: SDK, Link, Mode, Signal, Battery
            TopHudBar(state)

            // Warning banners (animated, appear when needed)
            WarningBanners(state)

            // Middle section: left telemetry + center + right controls
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
                // Left panel: altitude, speed, heading
                if (state.productConnected) {
                    LeftTelemetryPanel(state)
                }

                Spacer(modifier = Modifier.weight(1f))

                // Right side: action buttons + recording + telemetry
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    // Action buttons row
                    Column(
                        modifier = Modifier.padding(end = 8.dp, top = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Select Object button
                        ActionButton(
                            label = if (state.isSelectionMode) "DONE" else "SELECT",
                            color = if (state.isSelectionMode) Color.Cyan else Color(0xFF90CAF9),
                            onClick = { viewModel.toggleSelectionMode() }
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Clear selection (only when there's a selection)
                        if (state.selectedRegion != null) {
                            ActionButton(
                                label = "CLEAR",
                                color = Color(0xFFEF9A9A),
                                onClick = { viewModel.clearSelection() }
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        // Recording controls
                        RecordingControls(
                            isRecordingOnDevice = state.isRecordingOnDevice,
                            isRecordingOnDrone = state.isRecordingOnDrone,
                            recordingTimeSeconds = state.recordingTimeSeconds,
                            isProductConnected = state.productConnected,
                            lastRecordingPath = state.lastRecordingPath,
                            onStartRecording = { viewModel.startRecording() },
                            onStopRecording = { viewModel.stopRecording() },
                            onOpenLastRecording = {
                                openLastRecording(context, viewModel)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Right telemetry panel
                    if (state.productConnected) {
                        RightTelemetryPanel(state)
                    }
                }
            }

            // Bottom bar: telemetry numbers
            BottomTelemetryBar(state)
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.3f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun openLastRecording(context: android.content.Context, viewModel: MainViewModel) {
    val path = viewModel.getLastRecordingPath()
    if (path.isEmpty()) return

    val file = File(path)
    if (!file.exists()) {
        android.widget.Toast.makeText(context, "Recording file not found", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open recording"))
    } catch (e: Exception) {
        android.util.Log.w("FlightScreen", "FileProvider open failed, trying fallback: ${e.message}")
        // Fallback: open the DroneCaptures folder in the system file manager
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/document/primary:Movies%2FDroneCaptures"),
                    "resource/folder"
                )
            }
            context.startActivity(intent)
        } catch (e2: Exception) {
            android.util.Log.e("FlightScreen", "Fallback open also failed: ${e2.message}")
            android.widget.Toast.makeText(
                context,
                "Can't open file. Check Movies/DroneCaptures on your phone.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
