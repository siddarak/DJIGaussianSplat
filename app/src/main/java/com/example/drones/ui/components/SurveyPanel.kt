package com.example.drones.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.drones.localization.SurveyController
import com.example.drones.localization.WaypointEntry
import kotlin.math.max

/**
 * Right-side survey panel — visible only when survey mode is ON.
 *
 * Shows:
 *   - Top-down map preview of captured waypoints
 *   - Chronological list of waypoint IDs
 *   - Add-waypoint CTA when a candidate is detected
 *   - Reset / Done controls
 */
@Composable
fun SurveyPanel(
    waypoints: List<WaypointEntry>,
    candidate: SurveyController.Candidate?,
    onAddWaypoint: () -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(220.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header
        Text(
            text = "SURVEY  ${waypoints.size} WP",
            color = Color(0xFFFFD600),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

        // Top-down map
        TopDownPreview(
            waypoints = waypoints,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0D1B2A))
        )

        // Chronological list
        if (waypoints.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                waypoints.forEach { wp ->
                    Text(
                        text = "WP${wp.seq}  ID${wp.id}  (%.2f, %.2f)".format(wp.x, wp.y),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Add-waypoint CTA
        if (candidate != null) {
            val seq = waypoints.size
            val refLabel = candidate.referenceLockedId?.let { " ref WP id=$it" } ?: " (origin)"
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF76FF03))
                    .clickable { onAddWaypoint() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "ADD WP$seq · ID ${candidate.markerId}",
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "(%.2f, %.2f)%s".format(
                            candidate.proposedWorld.first, candidate.proposedWorld.second, refLabel
                        ),
                        color = Color.Black.copy(alpha = 0.7f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        } else {
            Text(
                text = if (waypoints.isEmpty())
                    "Show first marker to start"
                else
                    "Show new marker w/ a known one",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Reset + Done
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallChip("RESET", Color(0xFFEF9A9A), onReset, modifier = Modifier.weight(1f))
            SmallChip(
                if (waypoints.size >= 3) "DONE" else "DONE\n(need 3+)",
                if (waypoints.size >= 3) Color(0xFF76FF03) else Color.DarkGray,
                onDone,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SmallChip(label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.85f))
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.Black,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 2D top-down view of waypoint X-Y world positions.
 * Auto-scales to fit. Origin (first waypoint) marked. Lines connect chronological order.
 */
@Composable
private fun TopDownPreview(
    waypoints: List<WaypointEntry>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (waypoints.isEmpty()) {
            // Empty grid hint
            val gridPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(80, 255, 255, 255)
                strokeWidth = 1f
            }
            drawContext.canvas.nativeCanvas.apply {
                drawLine(size.width / 2, 0f, size.width / 2, size.height, gridPaint)
                drawLine(0f, size.height / 2, size.width, size.height / 2, gridPaint)
            }
            return@Canvas
        }

        // Compute bounds with padding
        val xs = waypoints.map { it.x }
        val ys = waypoints.map { it.y }
        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
        val rangeX = max(maxX - minX, 1.0)
        val rangeY = max(maxY - minY, 1.0)
        val range = max(rangeX, rangeY) * 1.3   // 30% padding
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0

        fun toScreen(x: Double, y: Double): Offset {
            val sx = ((x - cx) / range + 0.5) * size.width.toDouble()
            // Y inverted: world Y north → screen up (negative Y)
            val sy = (0.5 - (y - cy) / range) * size.height.toDouble()
            return Offset(sx.toFloat(), sy.toFloat())
        }

        // Connect chronological order with lines
        for (i in 0 until waypoints.size - 1) {
            val a = toScreen(waypoints[i].x, waypoints[i].y)
            val b = toScreen(waypoints[i + 1].x, waypoints[i + 1].y)
            drawLine(Color.White.copy(alpha = 0.5f), a, b, strokeWidth = 2f)
        }
        // Close polygon if 3+
        if (waypoints.size >= 3) {
            val a = toScreen(waypoints.last().x, waypoints.last().y)
            val b = toScreen(waypoints.first().x, waypoints.first().y)
            drawLine(Color.Yellow.copy(alpha = 0.4f), a, b, strokeWidth = 1f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }

        // Markers
        waypoints.forEach { wp ->
            val p = toScreen(wp.x, wp.y)
            val isOrigin = wp.seq == 0
            drawCircle(
                color = if (isOrigin) Color(0xFF76FF03) else Color(0xFFFFD600),
                radius = if (isOrigin) 8f else 6f,
                center = p,
                style = if (isOrigin) Stroke(width = 2f) else Stroke(width = 0f)
            )
            // Label
            val labelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 22f
                isAntiAlias = true
                isFakeBoldText = true
            }
            drawContext.canvas.nativeCanvas.drawText(
                "WP${wp.seq}", p.x + 8f, p.y - 8f, labelPaint
            )
        }
    }
}
