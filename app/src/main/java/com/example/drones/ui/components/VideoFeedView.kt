package com.example.drones.ui.components

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.drones.detection.DetectionResult
import com.example.drones.localization.MarkerObservation
import com.example.drones.sdk.VideoStreamManager

/** Colors per detection slot — cycles through 5 distinct colors */
private val BOX_COLORS = listOf(
    Color(0xFF00E5FF),   // cyan
    Color(0xFF76FF03),   // green
    Color(0xFFFF6D00),   // orange
    Color(0xFFE040FB),   // purple
    Color(0xFFFFD600),   // yellow
)

@Composable
fun VideoFeedView(
    modifier: Modifier = Modifier,
    isProductConnected: Boolean,
    detections: List<DetectionResult> = emptyList(),
    selectedId: Int? = null,
    modelLoaded: Boolean = false,
    framesReceived: Long = 0L,
    modelErrorText: String? = null,
    detectionDebugInfo: String = "",
    onObjectTapped: (DetectionResult) -> Unit = {},
    markers: List<MarkerObservation> = emptyList(),
    previewPath: com.example.drones.localization.ProjectedPath? = null,
    sourceFrameWidth: Int = 1920,
    sourceFrameHeight: Int = 1080
) {
    var surfaceReady by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // --- Video surface ---
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            VideoStreamManager.startVideoFeed(Surface(st), w, h)
                            surfaceReady = true
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            VideoStreamManager.startVideoFeed(Surface(st), w, h)
                        }
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            VideoStreamManager.stopVideoFeed()
                            surfaceReady = false
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            update = {}
        )

        // --- Bounding box overlay ---
        if (detections.isNotEmpty()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(detections) {
                        detectTapGestures { tapOffset ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            // Find which detection box was tapped
                            val hit = detections.firstOrNull { det ->
                                val left   = det.boxNorm.left   * w
                                val top    = det.boxNorm.top    * h
                                val right  = det.boxNorm.right  * w
                                val bottom = det.boxNorm.bottom * h
                                tapOffset.x in left..right && tapOffset.y in top..bottom
                            }
                            hit?.let { onObjectTapped(it) }
                        }
                    }
            ) {
                val w = size.width
                val h = size.height

                detections.forEachIndexed { idx, det ->
                    val color = BOX_COLORS[idx % BOX_COLORS.size]
                    val isSelected = det.trackId == selectedId
                    val strokeWidth = if (isSelected) 6f else 3f
                    val boxColor = if (isSelected) Color.White else color

                    val left   = det.boxNorm.left   * w
                    val top    = det.boxNorm.top    * h
                    val right  = det.boxNorm.right  * w
                    val bottom = det.boxNorm.bottom * h

                    // Box
                    drawRect(
                        color = boxColor,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = strokeWidth)
                    )

                    // Corner accents for selected box
                    if (isSelected) {
                        val cs = 24f
                        drawLine(Color.White, Offset(left, top), Offset(left + cs, top), 6f)
                        drawLine(Color.White, Offset(left, top), Offset(left, top + cs), 6f)
                        drawLine(Color.White, Offset(right, top), Offset(right - cs, top), 6f)
                        drawLine(Color.White, Offset(right, top), Offset(right, top + cs), 6f)
                        drawLine(Color.White, Offset(left, bottom), Offset(left + cs, bottom), 6f)
                        drawLine(Color.White, Offset(left, bottom), Offset(left, bottom - cs), 6f)
                        drawLine(Color.White, Offset(right, bottom), Offset(right - cs, bottom), 6f)
                        drawLine(Color.White, Offset(right, bottom), Offset(right, bottom - cs), 6f)
                    }

                    // Label background + text via native canvas
                    val label = "${det.label} ${"%.0f".format(det.confidence * 100)}%"
                    val paint = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.argb(180, 0, 0, 0)
                        style = android.graphics.Paint.Style.FILL
                    }
                    val textPaint = android.graphics.Paint().apply {
                        this.color = android.graphics.Color.WHITE
                        textSize = 36f
                        isFakeBoldText = isSelected
                        isAntiAlias = true
                    }
                    val textWidth = textPaint.measureText(label)
                    val textHeight = 44f
                    val labelTop = (top - textHeight - 4f).coerceAtLeast(0f)
                    drawContext.canvas.nativeCanvas.apply {
                        drawRect(left, labelTop, left + textWidth + 12f, labelTop + textHeight, paint)
                        drawText(label, left + 6f, labelTop + textHeight - 8f, textPaint)
                    }
                }
            }
        }

        // --- ArUco marker overlay: light-blue fill = "identified & held in memory" ---
        if (markers.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val sx = size.width / sourceFrameWidth.toFloat()
                val sy = size.height / sourceFrameHeight.toFloat()
                val lightBlue = Color(0xFF40C4FF)
                val markerPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 56f
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                val bgPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(200, 0, 0, 0)
                }

                markers.forEach { m ->
                    val pts = m.cornersPx.map { Offset(it.x * sx, it.y * sy) }
                    // Filled translucent light-blue quad — visual "I see you"
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        lineTo(pts[1].x, pts[1].y)
                        lineTo(pts[2].x, pts[2].y)
                        lineTo(pts[3].x, pts[3].y)
                        close()
                    }
                    drawPath(path, lightBlue.copy(alpha = 0.35f))
                    // Solid light-blue outline
                    for (i in 0 until 4) {
                        drawLine(lightBlue, pts[i], pts[(i + 1) % 4], strokeWidth = 5f)
                    }
                    // ID + distance label
                    val cx = pts.map { it.x }.average().toFloat()
                    val cy = pts.map { it.y }.average().toFloat()
                    val label = "ID ${m.id}  ${"%.2f".format(m.distanceM)}m"
                    val w = markerPaint.measureText(label)
                    drawContext.canvas.nativeCanvas.apply {
                        drawRect(cx - w / 2 - 8f, cy - 36f, cx + w / 2 + 8f, cy + 16f, bgPaint)
                        drawText(label, cx - w / 2, cy + 4f, markerPaint)
                    }
                }
            }
        }

        // --- AR flight-path preview: planned rings projected onto the live image ---
        if (previewPath != null && previewPath.rings.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val sx = size.width / sourceFrameWidth.toFloat()
                val sy = size.height / sourceFrameHeight.toFloat()

                previewPath.rings.forEach { ring ->
                    val col = Color(ring.colorArgb)
                    val pts = ring.pointsPx
                    for (i in 0 until pts.size - 1) {
                        drawLine(
                            col,
                            Offset(pts[i].x * sx, pts[i].y * sy),
                            Offset(pts[i + 1].x * sx, pts[i + 1].y * sy),
                            strokeWidth = 4f
                        )
                    }
                    // ring label near its first point
                    pts.firstOrNull()?.let { p ->
                        val lp = android.graphics.Paint().apply {
                            color = ring.colorArgb; textSize = 30f; isAntiAlias = true; isFakeBoldText = true
                        }
                        drawContext.canvas.nativeCanvas.drawText(ring.label, p.x * sx + 6f, p.y * sy, lp)
                    }
                }
                // center marker
                previewPath.centerPx?.let { c ->
                    val cp = Offset(c.x * sx, c.y * sy)
                    val red = Color(0xFFFF1744)
                    drawLine(red, Offset(cp.x - 14f, cp.y), Offset(cp.x + 14f, cp.y), strokeWidth = 4f)
                    drawLine(red, Offset(cp.x, cp.y - 14f), Offset(cp.x, cp.y + 14f), strokeWidth = 4f)
                    drawCircle(red, 6f, cp)
                }
            }
        }

        // No-video hint
        if (!isProductConnected || !surfaceReady) {
            Text(
                text = when {
                    !surfaceReady -> "Initializing video..."
                    !isProductConnected -> "Waiting for drone..."
                    else -> ""
                },
                color = Color.White.copy(alpha = 0.6f)
            )
        }

        // Tap hint when detections are visible but nothing selected
        if (detections.isNotEmpty() && selectedId == null) {
            Text(
                text = "Tap object to select orbit target",
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // Detection status chip — always visible so you can diagnose issues
        if (isProductConnected) {
            val modelError = (if (!modelLoaded) modelErrorText else null)
            val statusText = when {
                modelError != null         -> "LOAD FAIL: $modelError"
                !modelLoaded               -> "MODEL LOADING..."
                framesReceived == 0L       -> "WAITING FOR FRAMES"
                detections.isEmpty()       -> "DETECTING... $detectionDebugInfo"
                else                       -> "${detections.size} OBJECT${if (detections.size > 1) "S" else ""} FOUND"
            }
            val statusColor = when {
                modelError != null   -> Color.Red
                !modelLoaded         -> Color.Yellow
                framesReceived == 0L -> Color(0xFFFF6D00)  // orange
                detections.isEmpty() -> Color.White.copy(alpha = 0.6f)
                else                 -> Color(0xFF76FF03)   // green
            }
            Text(
                text = statusText,
                color = statusColor,
                fontSize = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }

        DisposableEffect(Unit) {
            onDispose { VideoStreamManager.cleanup() }
        }
    }
}
