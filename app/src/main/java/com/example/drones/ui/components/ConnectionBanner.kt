package com.example.drones.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
 * Warning banners that appear below the top HUD bar when something needs attention.
 *
 * Priority (highest to lowest):
 * 1. Critical battery (<10%) — red, flashing
 * 2. Signal lost — red
 * 3. Low battery (<20%) — orange
 * 4. Drone disconnected — dark red
 */
@Composable
fun WarningBanners(state: DroneState) {
    // Critical battery
    AnimatedBanner(
        visible = state.criticalBatteryWarning && state.productConnected,
        text = "CRITICAL BATTERY — LAND IMMEDIATELY (${state.batteryPercent}%)",
        backgroundColor = Color.Red,
        textColor = Color.White
    )

    // Signal lost
    AnimatedBanner(
        visible = state.signalLost && state.productConnected,
        text = "SIGNAL LOST — DRONE MAY RTH",
        backgroundColor = Color(0xFFD32F2F),
        textColor = Color.White
    )

    // Low battery
    AnimatedBanner(
        visible = state.lowBatteryWarning && !state.criticalBatteryWarning && state.productConnected,
        text = "LOW BATTERY (${state.batteryPercent}%) — CONSIDER LANDING",
        backgroundColor = Color(0xFFFF6F00),
        textColor = Color.White
    )

    // Drone disconnected (only show after SDK registered)
    AnimatedBanner(
        visible = !state.productConnected && state.sdkRegistered,
        text = "DRONE DISCONNECTED — Plug phone into RC-N3",
        backgroundColor = Color(0xFF424242),
        textColor = Color(0xFFBDBDBD)
    )
}

@Composable
private fun AnimatedBanner(
    visible: Boolean,
    text: String,
    backgroundColor: Color,
    textColor: Color
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor.copy(alpha = 0.9f))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
