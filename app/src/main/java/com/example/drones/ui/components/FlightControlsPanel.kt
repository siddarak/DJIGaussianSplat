package com.example.drones.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.drones.data.DroneState

/**
 * Left-side flight control panel.
 *
 * Layout (top to bottom):
 *  ┌─────────┐
 *  │ TAKEOFF │  ← changes to LAND once flying
 *  │   RTH   │  ← changes to CANCEL RTH when RTH active
 *  │ GIMBAL  │  ← lock / unlock / reset / point down
 *  └─────────┘
 *
 * Emergency stop is behind a long-press confirmation dialog — prevents accidental trigger.
 */
@Composable
fun FlightControlsPanel(
    state: DroneState,
    onTakeOff: () -> Unit,
    onLand: () -> Unit,
    onRth: () -> Unit,
    onCancelRth: () -> Unit,
    onConfirmLanding: () -> Unit,
    onEmergencyStop: () -> Unit,
    onLockGimbal: () -> Unit,
    onUnlockGimbal: () -> Unit,
    onResetGimbal: () -> Unit,
    onGimbalPointDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showEmergencyDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .background(
                Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Takeoff / Land ---
        when {
            state.isLandingConfirmationRequired -> {
                FlightButton(
                    label = "CONFIRM\nLAND",
                    color = Color(0xFFFF9800),
                    onClick = onConfirmLanding
                )
            }
            state.isLanding -> {
                FlightButton(label = "LANDING\n...", color = Color(0xFFFF9800), enabled = false)
            }
            state.isTakingOff -> {
                FlightButton(label = "TAKING\nOFF...", color = Color(0xFF66BB6A), enabled = false)
            }
            state.isFlying -> {
                FlightButton(
                    label = "LAND",
                    color = Color(0xFFFF9800),
                    onClick = onLand
                )
            }
            else -> {
                FlightButton(
                    label = "TAKE\nOFF",
                    color = Color(0xFF66BB6A),
                    enabled = state.productConnected,
                    onClick = onTakeOff
                )
            }
        }

        // --- RTH ---
        if (state.isRth) {
            FlightButton(
                label = "CANCEL\nRTH",
                color = Color(0xFFEF9A9A),
                onClick = onCancelRth
            )
        } else {
            FlightButton(
                label = "RTH",
                color = Color(0xFF90CAF9),
                enabled = state.productConnected && state.isFlying,
                onClick = onRth
            )
        }

        // --- Gimbal controls ---
        GimbalControlSection(
            state = state,
            onLock = onLockGimbal,
            onUnlock = onUnlockGimbal,
            onReset = onResetGimbal,
            onPointDown = onGimbalPointDown
        )

        Spacer(modifier = Modifier.height(4.dp))

        // --- Emergency Stop --- (red, always visible when flying)
        AnimatedVisibility(
            visible = state.isFlying || state.isTakingOff,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            FlightButton(
                label = "E-STOP",
                color = Color.Red,
                onClick = { showEmergencyDialog = true },
                isBordered = true
            )
        }

        // Flight action error
        state.flightActionError?.let { err ->
            Text(
                text = err,
                color = Color.Red,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .widthIn(max = 80.dp)
                    .padding(top = 2.dp)
            )
        }
    }

    // Emergency stop confirmation dialog
    if (showEmergencyDialog) {
        EmergencyStopDialog(
            onConfirm = {
                showEmergencyDialog = false
                onEmergencyStop()
            },
            onDismiss = { showEmergencyDialog = false }
        )
    }
}

@Composable
private fun GimbalControlSection(
    state: DroneState,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
    onReset: () -> Unit,
    onPointDown: () -> Unit
) {
    Column(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Label + current angle
        Text(
            text = "GIMBAL",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "%.0f°".format(state.gimbalPitch),
            color = if (state.gimbalLocked) Color.Cyan else Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        if (state.gimbalLocked) {
            Text(
                text = "LOCKED @%.0f°".format(state.gimbalLockAngle),
                color = Color.Cyan,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Lock / Unlock
        if (state.gimbalLocked) {
            SmallButton(label = "UNLOCK", color = Color.Cyan, onClick = onUnlock)
        } else {
            SmallButton(
                label = "LOCK",
                color = Color(0xFF90CAF9),
                enabled = state.productConnected,
                onClick = onLock
            )
        }

        // Reset to level
        SmallButton(
            label = "LEVEL",
            color = Color.White.copy(alpha = 0.7f),
            enabled = state.productConnected,
            onClick = onReset
        )

        // Point down
        SmallButton(
            label = "↓ DOWN",
            color = Color.White.copy(alpha = 0.7f),
            enabled = state.productConnected,
            onClick = onPointDown
        )
    }
}

@Composable
private fun FlightButton(
    label: String,
    color: Color,
    enabled: Boolean = true,
    isBordered: Boolean = false,
    onClick: () -> Unit = {}
) {
    val bgColor = if (enabled) color.copy(alpha = 0.25f) else Color.DarkGray.copy(alpha = 0.3f)
    val textColor = if (enabled) color else Color.Gray

    val modifier = Modifier
        .clip(RoundedCornerShape(6.dp))
        .background(bgColor)
        .then(
            if (isBordered) Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            else Modifier
        )
        .clickable(enabled = enabled) { onClick() }
        .padding(horizontal = 14.dp, vertical = 8.dp)

    Text(
        text = label,
        color = textColor,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
        lineHeight = 14.sp
    )
}

@Composable
private fun SmallButton(
    label: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = if (enabled) color else Color.Gray,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (enabled) color.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun EmergencyStopDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1A0000), RoundedCornerShape(12.dp))
                .border(2.dp, Color.Red, RoundedCornerShape(12.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "⚠ EMERGENCY STOP",
                color = Color.Red,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Motors will cut immediately.\nDrone WILL fall.\n\nOnly use to prevent collision.",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 18.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "CANCEL",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.DarkGray)
                        .clickable { onDismiss() }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "STOP MOTORS",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Red)
                        .clickable { onConfirm() }
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }
    }
}
