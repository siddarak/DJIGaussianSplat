package com.example.drones.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.drones.localization.MarkerObservation
import com.example.drones.localization.MarkerRole
import com.example.drones.localization.TopScanResult
import kotlin.math.max

/**
 * Survey panel — top-scan flow.
 *
 * Before scan: shows how many markers are currently in view + a SCAN button.
 * After scan: shows the ground-plane map (markers + geometric center +
 * keep-out / outer rings) and a RESET button.
 */
@Composable
fun SurveyPanel(
    liveObservations: List<MarkerObservation>,
    scan: TopScanResult?,
    onScan: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(220.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (scan == null) "SURVEY  ${liveObservations.size} seen"
            else "SCAN  ${scan.markers.size} markers",
            color = Color(0xFFFFD600),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

        TopDownMap(
            scan = scan,
            liveCount = liveObservations.size,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0D1B2A))
        )

        if (scan != null) {
            Text(
                text = "center camH %.2fm\nkeep-out %.2fm  outer %.2fm\n%s"
                    .format(scan.cameraHeightM, scan.rKeepoutM, scan.rOuterM,
                        if (scan.source == TopScanResult.Source.MEASURED) "measured positions" else "scan-derived"),
                color = Color(0xFF80DEEA),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "ids: ${scan.seenIds.joinToString(",")}",
                color = Color.White,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        } else {
            Text(
                text = "Climb until all markers in frame, then SCAN",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (scan == null) {
                Chip("SCAN", if (liveObservations.isNotEmpty()) Color(0xFF76FF03) else Color.DarkGray,
                    onScan, Modifier.weight(1f))
            } else {
                Chip("RESCAN", Color(0xFF90CAF9), onReset, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Chip(label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.85f))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.Black, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

/** Top-down ground-plane view of the scan: markers, center, keep-out + outer rings. */
@Composable
private fun TopDownMap(
    scan: TopScanResult?,
    liveCount: Int,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        // crosshair grid
        val grid = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(60, 255, 255, 255); strokeWidth = 1f
        }
        drawContext.canvas.nativeCanvas.apply {
            drawLine(size.width / 2, 0f, size.width / 2, size.height, grid)
            drawLine(0f, size.height / 2, size.width, size.height / 2, grid)
        }
        if (scan == null || scan.markers.isEmpty()) return@Canvas

        // bounds: include outer ring radius so circles fit
        val span = max(scan.rOuterM, scan.rKeepoutM) * 2.4
        val scale = (size.minDimension / span).toFloat()
        fun toScreen(x: Double, y: Double): Offset {
            val sx = size.width / 2f + ((x - scan.centerXM) * scale).toFloat()
            val sy = size.height / 2f - ((y - scan.centerYM) * scale).toFloat()  // Y up
            return Offset(sx, sy)
        }

        val center = toScreen(scan.centerXM, scan.centerYM)

        // keep-out (red) + outer (cyan) rings
        drawCircle(Color(0xFFFF5252).copy(alpha = 0.5f), (scan.rKeepoutM * scale).toFloat(),
            center, style = Stroke(width = 2f))
        drawCircle(Color(0xFF40C4FF).copy(alpha = 0.4f), (scan.rOuterM * scale).toFloat(),
            center, style = Stroke(width = 1.5f))

        // center crosshair (object/orbit focus)
        val cc = Color(0xFFFF1744)
        drawLine(cc, Offset(center.x - 7f, center.y), Offset(center.x + 7f, center.y), 2f)
        drawLine(cc, Offset(center.x, center.y - 7f), Offset(center.x, center.y + 7f), 2f)

        // markers
        scan.markers.forEach { m ->
            val p = toScreen(m.xM, m.yM)
            val col = if (m.role == MarkerRole.INNER) Color(0xFF76FF03) else Color(0xFFFFD600)
            drawCircle(col, 6f, p)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE; textSize = 20f; isAntiAlias = true; isFakeBoldText = true
            }
            drawContext.canvas.nativeCanvas.drawText("${m.id}", p.x + 7f, p.y - 7f, paint)
        }
    }
}
