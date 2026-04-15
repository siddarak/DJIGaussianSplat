package com.example.drones.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import com.example.drones.orbit.OrbitState
import com.example.drones.ui.MainViewModel
import com.example.drones.ui.components.BottomTelemetryBar
import com.example.drones.ui.components.FlightControlsPanel
import com.example.drones.ui.components.ObjectSelector
import com.example.drones.ui.components.RecordingControls
import com.example.drones.ui.components.RecordingDebugOverlay
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
        // Layer 0: Full-screen video feed with live detection overlay
        VideoFeedView(
            modifier = Modifier.fillMaxSize(),
            isProductConnected = state.productConnected,
            detections = state.detections,
            selectedId = state.selectedDetectionId,
            onObjectTapped = { det -> viewModel.selectDetection(det) }
        )

        // Layer 1: Object selector overlay
        ObjectSelector(
            selectedRegion = state.selectedRegion,
            isSelectionMode = state.isSelectionMode,
            onRegionSelected = { viewModel.selectRegion(it) },
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2: HUD
        Column(modifier = Modifier.fillMaxSize()) {

            TopHudBar(state)
            WarningBanners(state)

            // Middle row: left controls | spacer | right controls
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
                // LEFT — flight controls
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

                Spacer(modifier = Modifier.weight(1f))

                // RIGHT — recording + object selection + debug
                Column(
                    modifier = Modifier.padding(end = 8.dp, top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Object SELECT / DONE
                    ActionButton(
                        label = if (state.isSelectionMode) "DONE" else "SELECT",
                        color = if (state.isSelectionMode) Color.Cyan else Color(0xFF90CAF9),
                        onClick = { viewModel.toggleSelectionMode() }
                    )

                    if (state.selectedRegion != null && !state.isSelectionMode) {
                        ActionButton(
                            label = "CLEAR",
                            color = Color(0xFFEF9A9A),
                            onClick = { viewModel.clearSelection() }
                        )
                    }

                    // Orbit controls
                    OrbitControls(
                        state = state,
                        onLockOrbit = { viewModel.lockOrbitTarget() },
                        onAbortOrbit = { viewModel.abortOrbit() }
                    )

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

                    // Debug toggle button
                    ActionButton(
                        label = "DEBUG",
                        color = Color.Gray,
                        onClick = { viewModel.toggleDebugOverlay() }
                    )
                }
            }

            BottomTelemetryBar(state)
        }

        // Layer 3: Debug overlay (centered, on top of everything)
        if (state.showDebugOverlay) {
            RecordingDebugOverlay(
                onDismiss = { viewModel.toggleDebugOverlay() },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun OrbitControls(
    state: com.example.drones.data.DroneState,
    onLockOrbit: () -> Unit,
    onAbortOrbit: () -> Unit
) {
    val orbitState = state.orbitState
    val isOrbiting = orbitState is OrbitState.Flying ||
            orbitState is OrbitState.Climbing ||
            orbitState == OrbitState.TopShot ||
            orbitState == OrbitState.Arming

    if (isOrbiting) {
        // Abort button + status while orbiting
        val label = when (orbitState) {
            is OrbitState.Arming   -> "ARM..."
            is OrbitState.Climbing -> "CLIMB"
            is OrbitState.Flying   -> {
                val pct = orbitState.progressDeg.toInt()
                "${orbitState.ring.label} $pct°"
            }
            OrbitState.TopShot -> "TOP"
            else -> "ORBIT"
        }
        ActionButton(label = label, color = Color(0xFFFFD600), onClick = {})
        ActionButton(label = "ABORT", color = Color.Red, onClick = onAbortOrbit)
    } else {
        // Lock button — only active when drone is flying and target selected
        val canLock = state.productConnected && state.isFlying &&
                state.forwardObstacleDistM in 0.38f..20f
        val lockColor = when {
            !state.productConnected || !state.isFlying -> Color.Gray
            state.selectedDetectionId != null -> Color(0xFF76FF03)  // green — target selected
            state.forwardObstacleDistM in 0.38f..20f -> Color(0xFF90CAF9)  // blue — sensor ready
            else -> Color.Gray
        }
        val sensorText = if (state.forwardObstacleDistM > 0)
            "%.1fm".format(state.forwardObstacleDistM) else "---"

        ActionButton(
            label = "ORBIT\n$sensorText",
            color = lockColor,
            onClick = { if (canLock) onLockOrbit() }
        )

        if (orbitState is OrbitState.Done) {
            ActionButton(label = "DONE!", color = Color(0xFF76FF03), onClick = {})
        }
        if (orbitState is OrbitState.Error) {
            ActionButton(label = "ERR", color = Color.Red, onClick = {})
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
    // Try MediaStore URI first (works with scoped storage)
    val uri = viewModel.getLastRecordingUri()
    if (uri != null) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open recording"))
            return
        } catch (e: Exception) {
            android.util.Log.w("FlightScreen", "MediaStore URI failed: ${e.message}")
        }
    }

    // Fallback to file path
    val path = viewModel.getLastRecordingPath()
    if (path.isEmpty()) return

    val file = File(path)
    if (!file.exists()) {
        Toast.makeText(context, "Recording not found on device", Toast.LENGTH_SHORT).show()
        return
    }

    try {
        val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open recording"))
    } catch (e: Exception) {
        Toast.makeText(
            context,
            "Check Movies/DroneCaptures in your file manager",
            Toast.LENGTH_LONG
        ).show()
    }
}
