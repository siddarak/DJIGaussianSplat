package com.example.drones.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.drones.data.DroneState

/**
 * Top status bar: connection, SDK, flight mode, signal, battery
 */
@Composable
fun TopHudBar(state: DroneState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // SDK status
        HudChip(
            label = "SDK",
            value = if (state.sdkRegistered) "OK" else "...",
            valueColor = if (state.sdkRegistered) Color.Green else Color.Yellow
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Drone connection
        HudChip(
            label = "LINK",
            value = if (state.productConnected) "ON" else "OFF",
            valueColor = if (state.productConnected) Color.Green else Color.Red
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Flight mode
        if (state.productConnected) {
            HudChip(
                label = "MODE",
                value = state.flightMode,
                valueColor = when {
                    state.isFlying -> Color.Cyan
                    else -> Color.White
                }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Signal quality
        if (state.productConnected) {
            SignalIndicator(quality = state.signalQuality)
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Battery
        BatteryIndicator(
            percent = state.batteryPercent,
            lowWarning = state.lowBatteryWarning,
            criticalWarning = state.criticalBatteryWarning,
            connected = state.productConnected
        )

        // Error
        state.errorMessage?.let { error ->
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = error,
                color = Color.Red,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

/**
 * Bottom telemetry bar: altitude, speed, heading, GPS, gimbal
 */
@Composable
fun BottomTelemetryBar(state: DroneState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TelemetryItem(label = "ALT", value = "%.1fm".format(state.altitude))
        TelemetryItem(label = "H.SPD", value = "%.1fm/s".format(state.speedHorizontal))
        TelemetryItem(label = "V.SPD", value = "%.1fm/s".format(state.speedVertical))
        TelemetryItem(label = "HDG", value = "%03.0f\u00B0".format(
            if (state.heading < 0) state.heading + 360 else state.heading
        ))
        TelemetryItem(label = "SAT", value = "${state.satelliteCount}",
            valueColor = when {
                state.satelliteCount >= 10 -> Color.Green
                state.satelliteCount >= 6 -> Color.Yellow
                else -> Color.Red
            }
        )
        TelemetryItem(label = "GIMBAL", value = "%.0f\u00B0".format(state.gimbalPitch))
    }
}

/**
 * Left-side telemetry panel (vertical) — for landscape mode
 */
@Composable
fun LeftTelemetryPanel(state: DroneState) {
    Column(
        modifier = Modifier
            .background(
                Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TelemetryItem(label = "ALT", value = "%.1f m".format(state.altitude))
        TelemetryItem(label = "H.SPD", value = "%.1f m/s".format(state.speedHorizontal))
        TelemetryItem(label = "V.SPD", value = "%.1f m/s".format(state.speedVertical))
        TelemetryItem(label = "HDG", value = "%03.0f\u00B0".format(
            if (state.heading < 0) state.heading + 360 else state.heading
        ))
    }
}

/**
 * Right-side panel — GPS + gimbal info
 */
@Composable
fun RightTelemetryPanel(state: DroneState) {
    Column(
        modifier = Modifier
            .background(
                Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.End
    ) {
        TelemetryItem(
            label = "SAT",
            value = "${state.satelliteCount}",
            valueColor = when {
                state.satelliteCount >= 10 -> Color.Green
                state.satelliteCount >= 6 -> Color.Yellow
                else -> Color.Red
            }
        )
        TelemetryItem(label = "GIMBAL", value = "%.0f\u00B0".format(state.gimbalPitch))
        if (state.isFlying) {
            TelemetryItem(
                label = "FLY",
                value = "ACTIVE",
                valueColor = Color.Cyan
            )
        }
    }
}

// --- Reusable components ---

@Composable
fun HudChip(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.3f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun TelemetryItem(
    label: String,
    value: String,
    valueColor: Color = Color.White
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun BatteryIndicator(
    percent: Int,
    lowWarning: Boolean,
    criticalWarning: Boolean,
    connected: Boolean
) {
    val color = when {
        !connected -> Color.Gray
        criticalWarning -> Color.Red
        lowWarning -> Color.Yellow
        else -> Color.Green
    }
    val displayPercent = if (connected) "${percent}%" else "--%"

    Row(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.3f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Simple battery bar
        Box(
            modifier = Modifier
                .size(width = 20.dp, height = 10.dp)
                .background(Color.DarkGray, RoundedCornerShape(2.dp))
        ) {
            Box(
                modifier = Modifier
                    .size(
                        width = (20.dp * (percent.coerceIn(0, 100) / 100f)),
                        height = 10.dp
                    )
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = displayPercent,
            color = color,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SignalIndicator(quality: Int) {
    val color = when {
        quality >= 70 -> Color.Green
        quality >= 40 -> Color.Yellow
        else -> Color.Red
    }
    HudChip(label = "SIG", value = "${quality}%", valueColor = color)
}
