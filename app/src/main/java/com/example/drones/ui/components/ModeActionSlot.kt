package com.example.drones.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.drones.data.DroneState
import com.example.drones.orbit.OrbitState

/**
 * Single mode-driven CTA at the bottom-center of the Cockpit.
 *
 * Replaces the right-side stacked button column. Content changes based on
 * current flight mode + drone state. Always exactly one button visible.
 *
 * Mode mapping (current — pre flight-mode-enum):
 *   - Orbit running → ABORT (red)
 *   - Orbit done    → DONE (green, decorative)
 *   - Flying + sensor in range + target selected → LOCK ORBIT (green)
 *   - Flying + sensor in range, no target → ORBIT (blue)
 *   - Otherwise → grey, disabled
 */
@Composable
fun ModeActionSlot(
    state: DroneState,
    onLockOrbit: () -> Unit,
    onAbortOrbit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val orbitState = state.orbitState
    val isOrbiting = orbitState is OrbitState.Flying ||
            orbitState is OrbitState.Climbing ||
            orbitState == OrbitState.TopShot ||
            orbitState == OrbitState.Arming

    when {
        isOrbiting -> CtaButton(
            label = "ABORT",
            sublabel = orbitProgressLabel(orbitState),
            color = Color.Red,
            onClick = onAbortOrbit,
            modifier = modifier
        )

        orbitState is OrbitState.Done ->
            CtaButton(label = "DONE", color = Color(0xFF76FF03), onClick = {}, enabled = false, modifier = modifier)

        orbitState is OrbitState.Error ->
            CtaButton(label = "ERROR", sublabel = orbitState.message.take(20), color = Color.Red, onClick = {}, enabled = false, modifier = modifier)

        else -> {
            val canLock = state.productConnected && state.isFlying &&
                    state.forwardObstacleDistM in 0.38f..20f
            val color = when {
                !state.productConnected || !state.isFlying -> Color.DarkGray
                state.selectedDetectionId != null && canLock -> Color(0xFF76FF03)   // green: target selected, ready
                canLock                                    -> Color(0xFF90CAF9)    // blue: sensor in range
                else                                        -> Color.DarkGray
            }
            val sub = when {
                !state.productConnected -> "no drone"
                !state.isFlying         -> "takeoff first"
                state.forwardObstacleDistM <= 0f -> "no sensor"
                state.forwardObstacleDistM < 0.38f -> "too close"
                state.forwardObstacleDistM > 20f -> "too far"
                else -> "%.1fm".format(state.forwardObstacleDistM)
            }
            CtaButton(
                label = if (state.selectedDetectionId != null) "LOCK ORBIT" else "ORBIT",
                sublabel = sub,
                color = color,
                onClick = { if (canLock) onLockOrbit() },
                enabled = canLock,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun CtaButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    sublabel: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val alpha = if (enabled) 0.95f else 0.4f
    Box(
        modifier = modifier
            .width(140.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = alpha))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = Color.Black,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    color = Color.Black.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

private fun orbitProgressLabel(s: OrbitState): String? = when (s) {
    is OrbitState.Arming   -> "ARM..."
    is OrbitState.Climbing -> "CLIMB"
    is OrbitState.Flying   -> "${s.ring.label} ${s.progressDeg.toInt()}°"
    OrbitState.TopShot     -> "TOP SHOT"
    else                    -> null
}
