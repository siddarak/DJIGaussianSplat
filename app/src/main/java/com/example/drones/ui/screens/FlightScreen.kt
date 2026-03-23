package com.example.drones.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.example.drones.ui.components.FlightControlsPanel
import com.example.drones.ui.components.ObjectSelector
import com.example.drones.ui.components.RecordingControls
import com.example.drones.ui.components.TopHudBar
import com.example.drones.ui.components.VideoFeedView
import com.example.drones.ui.components.WarningBanners
import java.io.File

/**
 * Main flight screen layout:
 *
 * ┌─────────────────────────────────────┐
 * │          TOP HUD BAR                │  SDK, Link, Mode, Signal, Battery
 * ├─────────────────────────────────────┤
 * │  WARNING BANNERS (animated)         │  Battery / signal / disconnect alerts
 * ├──────────┬──────────────┬───────────┤
 * │ FLIGHT   │              │ RECORD    │
 * │ CONTROLS │  VIDEO FEED  │ CONTROLS  │
 * │ (left)   │              │ + SELECT  │
 * │          │              │ (right)   │
 * ├─────────────────────────────────────┤
 * │          BOTTOM TELEMETRY BAR       │  ALT, H.SPD, V.SPD, HDG, SAT, GIMBAL
 * └─────────────────────────────────────┘
 *
 * Telemetry is shown ONCE — in the bottom bar only.
 * Left panel = flight controls. Right panel = recording + object select.
 */
@Composable
fun FlightScreen(viewModel: MainViewModel) {
    val state by viewModel.droneState.collectAsState()
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Layer 0: Full-screen video feed
        VideoFeedView(
            modifier = Modifier.fillMaxSize(),
            isProductConnected = state.productConnected
        )

        // Layer 1: Object selector overlay (below HUD, above video)
        ObjectSelector(
            selectedRegion = state.selectedRegion,
            isSelectionMode = state.isSelectionMode,
            onRegionSelected = { viewModel.selectRegion(it) },
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2: HUD
        Column(modifier = Modifier.fillMaxSize()) {

            // Top bar: SDK / LINK / MODE / SIG / BATTERY
            TopHudBar(state)

            // Animated warning banners
            WarningBanners(state)

            // Middle row: left controls | spacer | right controls
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
                // LEFT — flight controls (takeoff/land/RTH/gimbal/emergency)
                FlightControlsPanel(
                    state = state,
                    onTakeOff           = { viewModel.takeOff() },
                    onLand              = { viewModel.land() },
                    onRth               = { viewModel.returnToHome() },
                    onCancelRth         = { viewModel.cancelRth() },
                    onConfirmLanding    = { viewModel.confirmLanding() },
                    onEmergencyStop     = { viewModel.emergencyStop() },
                    onLockGimbal        = { viewModel.lockGimbal() },
                    onUnlockGimbal      = { viewModel.unlockGimbal() },
                    onResetGimbal       = { viewModel.resetGimbal() },
                    onGimbalPointDown   = { viewModel.setGimbalPitch(-90.0) }
                )

                // Center spacer — video feed shows through here
                Spacer(modifier = Modifier.weight(1f))

                // RIGHT — recording + object selection
                Column(
                    modifier = Modifier.padding(end = 8.dp, top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                ) {
                    // Object SELECT / DONE button
                    ActionButton(
                        label = if (state.isSelectionMode) "DONE" else "SELECT",
                        color = if (state.isSelectionMode) Color.Cyan else Color(0xFF90CAF9),
                        onClick = { viewModel.toggleSelectionMode() }
                    )

                    // CLEAR selection (only shown when something is selected)
                    if (state.selectedRegion != null && !state.isSelectionMode) {
                        ActionButton(
                            label = "CLEAR",
                            color = Color(0xFFEF9A9A),
                            onClick = { viewModel.clearSelection() }
                        )
                    }

                    // Recording controls
                    RecordingControls(
                        isRecordingOnDevice = state.isRecordingOnDevice,
                        isRecordingOnDrone  = state.isRecordingOnDrone,
                        recordingTimeSeconds = state.recordingTimeSeconds,
                        isProductConnected  = state.productConnected,
                        lastRecordingPath   = state.lastRecordingPath,
                        onStartRecording    = { viewModel.startRecording() },
                        onStopRecording     = { viewModel.stopRecording() },
                        onOpenLastRecording = { openLastRecording(context, viewModel) }
                    )
                }
            }

            // Bottom bar: all telemetry in one place (no duplicates)
            BottomTelemetryBar(state)
        }
    }
}

@Composable
private fun ActionButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.25f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

private fun openLastRecording(context: android.content.Context, viewModel: MainViewModel) {
    val path = viewModel.getLastRecordingPath()
    if (path.isEmpty()) return

    val file = File(path)
    if (!file.exists()) {
        Toast.makeText(context, "Recording not found on device", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open recording"))
    } catch (e: Exception) {
        android.util.Log.w("FlightScreen", "FileProvider failed, trying folder: ${e.message}")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/document/primary:Movies%2FDroneCaptures"),
                    "resource/folder"
                )
            })
        } catch (e2: Exception) {
            Toast.makeText(
                context,
                "Can't open file — check Movies/DroneCaptures",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
