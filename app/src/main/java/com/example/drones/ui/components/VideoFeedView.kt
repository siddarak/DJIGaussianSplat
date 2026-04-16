package com.example.drones.ui.components

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
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
    onObjectTapped: (DetectionResult) -> Unit = {}
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
            val statusText = when {
                !modelLoaded               -> "MODEL LOADING..."
                framesReceived == 0L       -> "WAITING FOR FRAMES"
                detections.isEmpty()       -> "DETECTING... (${framesReceived} frames)"
                else                       -> "${detections.size} OBJECT${if (detections.size > 1) "S" else ""} FOUND"
            }
            val statusColor = when {
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
