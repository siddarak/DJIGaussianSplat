package com.example.drones.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.drones.ui.MainViewModel
import com.example.drones.ui.components.BottomTelemetryBar
import com.example.drones.ui.components.FlightControlsPanel
import com.example.drones.ui.components.ModeActionSlot
import com.example.drones.ui.components.ObjectSelector
import com.example.drones.ui.components.RailButton
import com.example.drones.ui.components.RecordingDebugOverlay
import com.example.drones.ui.components.SurveyPanel
import com.example.drones.ui.components.TopHudBar
import com.example.drones.ui.components.VideoFeedView
import com.example.drones.ui.components.WarningBanners
import com.example.drones.util.FileBrowser
import java.io.File

/**
 * Cockpit — primary live-flight screen.
 *
 * Layout (left rail + center video + bottom CTA):
 *   ┌─[TopHud]──────────────────────────────[⚙]─┐
 *   │ rail │                                    │
 *   │ REC  │            LIVE VIDEO              │
 *   │ SEL  │                                    │
 *   │ DBG  │                                    │
 *   │ FILE │                                    │
 *   ├──────┴────────────────────────────────────┤
 *   │ FlightControls    [MODE-CTA]              │
 *   │ BottomTelemetryBar                        │
 *   └───────────────────────────────────────────┘
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
        // Layer 0: Full-screen video feed with live detection overlay
        VideoFeedView(
            modifier = Modifier.fillMaxSize(),
            isProductConnected = state.productConnected,
            detections = state.detections,
            selectedId = state.selectedDetectionId,
            modelLoaded = state.detectionModelLoaded,
            framesReceived = state.detectionFramesReceived,
            modelErrorText = state.detectionModelError,
            detectionDebugInfo = state.detectionDebugInfo,
            onObjectTapped = { det -> viewModel.selectDetection(det) },
            markers = state.markersDetected
        )

        // Layer 1: Object selector overlay (manual region selection)
        ObjectSelector(
            selectedRegion = state.selectedRegion,
            isSelectionMode = state.isSelectionMode,
            onRegionSelected = { viewModel.selectRegion(it) },
            modifier = Modifier.fillMaxSize()
        )

        // Layer 2: Cockpit chrome (HUD + rails + controls)
        Column(modifier = Modifier.fillMaxSize()) {

            TopHudBar(state)
            WarningBanners(state)

            // Center row: left rail | video | (right survey panel when survey mode on)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
                LeftRail(viewModel, state)
                Spacer(modifier = Modifier.weight(1f))

                if (state.surveyMode) {
                    SurveyPanel(
                        waypoints       = state.capturedWaypoints,
                        candidate       = state.surveyCandidate,
                        liveObservations = state.markersDetected,
                        onAddWaypoint   = { viewModel.addCurrentWaypoint() },
                        onReset         = { viewModel.resetSurvey() },
                        onDone          = { viewModel.finishSurvey() },
                        modifier        = Modifier.padding(end = 8.dp, top = 8.dp)
                    )
                }
            }

            // Bottom row: flight controls (left) + mode CTA (right)
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

                ModeActionSlot(
                    state = state,
                    onLockOrbit  = { viewModel.lockOrbitTarget() },
                    onAbortOrbit = { viewModel.abortOrbit() }
                )
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

/**
 * Left rail — always-visible 1-tap actions.
 *
 * Tools that don't depend on a flight mode: recording, region select,
 * debug overlay, log share. Mode-specific CTAs live in [ModeActionSlot].
 */
@Composable
private fun LeftRail(
    viewModel: MainViewModel,
    state: com.example.drones.data.DroneState
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .padding(start = 8.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Recording — pill rotates between OFF/ON
        RailButton(
            label = if (state.isRecordingOnDevice) "STOP" else "REC",
            sublabel = if (state.isRecordingOnDevice) "${state.recordingTimeSeconds}s" else null,
            accent = if (state.isRecordingOnDevice) Color(0xFFFF1744) else Color(0xFFFF6D00),
            active = state.isRecordingOnDevice,
            enabled = state.productConnected,
            onClick = {
                if (state.isRecordingOnDevice) viewModel.stopRecording()
                else viewModel.startRecording()
            }
        )

        // Open last recording
        if (!state.lastRecordingPath.isNullOrEmpty()) {
            RailButton(
                label = "PLAY",
                accent = Color(0xFF90CAF9),
                onClick = { openLastRecording(context, viewModel) }
            )
        }

        // Manual region select (legacy object picker)
        RailButton(
            label = if (state.isSelectionMode) "DONE" else "SEL",
            accent = if (state.isSelectionMode) Color.Cyan else Color(0xFF90CAF9),
            active = state.isSelectionMode,
            onClick = { viewModel.toggleSelectionMode() }
        )

        if (state.selectedRegion != null && !state.isSelectionMode) {
            RailButton(
                label = "CLR",
                accent = Color(0xFFEF9A9A),
                onClick = { viewModel.clearSelection() }
            )
        }

        // ArUco survey mode (mutually exclusive with object detection)
        RailButton(
            label = "SURV",
            sublabel = if (state.surveyMode) "${state.markersDetected.size}m" else null,
            accent = Color(0xFFFFD600),
            active = state.surveyMode,
            onClick = { viewModel.toggleSurveyMode() }
        )

        // Debug overlay
        RailButton(
            label = "DBG",
            accent = Color.Gray,
            active = state.showDebugOverlay,
            onClick = { viewModel.toggleDebugOverlay() }
        )

        // Log share / files
        RailButton(
            label = "FILE",
            accent = Color(0xFF1565C0),
            onClick = { FileBrowser.openDroneFolder(context) }
        )
    }
}

private fun openLastRecording(context: android.content.Context, viewModel: MainViewModel) {
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
