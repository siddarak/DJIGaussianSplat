package com.example.drones.ui.components

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Object selection overlay — allows user to draw a bounding box on the video feed.
 *
 * Interaction modes:
 * 1. Tap: places a small selection box centered on tap point
 * 2. Drag: draws a custom rectangle from drag start to drag end
 * 3. Tap on existing selection: clears it
 *
 * The selected region is normalized to 0..1 coordinates (relative to view size)
 * so it can be mapped to the actual camera frame regardless of display resolution.
 *
 * This is the foundation for Layer 2 (object detection):
 * - User selects a region → future YOLO model runs on that crop
 * - The normalized coords can be sent to the autonomous flight planner
 */
@Composable
fun ObjectSelector(
    selectedRegion: RectF?,
    isSelectionMode: Boolean,
    onRegionSelected: (RectF?) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var viewWidth by remember { mutableStateOf(1f) }
    var viewHeight by remember { mutableStateOf(1f) }

    Box(modifier = modifier.fillMaxSize()) {
        if (isSelectionMode) {
            // Touch capture layer
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        viewWidth = size.width.toFloat()
                        viewHeight = size.height.toFloat()

                        detectTapGestures { offset ->
                            if (selectedRegion != null) {
                                // Tap clears existing selection
                                onRegionSelected(null)
                            } else {
                                // Tap places a default-sized box (10% of screen)
                                val boxSize = 0.1f
                                val cx = offset.x / viewWidth
                                val cy = offset.y / viewHeight
                                onRegionSelected(RectF(
                                    (cx - boxSize / 2).coerceIn(0f, 1f),
                                    (cy - boxSize / 2).coerceIn(0f, 1f),
                                    (cx + boxSize / 2).coerceIn(0f, 1f),
                                    (cy + boxSize / 2).coerceIn(0f, 1f)
                                ))
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        viewWidth = size.width.toFloat()
                        viewHeight = size.height.toFloat()

                        detectDragGestures(
                            onDragStart = { offset ->
                                dragStart = offset
                                dragCurrent = offset
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                dragCurrent = change.position
                            },
                            onDragEnd = {
                                val start = dragStart
                                val end = dragCurrent
                                if (start != null && end != null) {
                                    val left = minOf(start.x, end.x) / viewWidth
                                    val top = minOf(start.y, end.y) / viewHeight
                                    val right = maxOf(start.x, end.x) / viewWidth
                                    val bottom = maxOf(start.y, end.y) / viewHeight

                                    // Only accept if selection is meaningful (>2% of screen)
                                    if ((right - left) > 0.02f && (bottom - top) > 0.02f) {
                                        onRegionSelected(RectF(
                                            left.coerceIn(0f, 1f),
                                            top.coerceIn(0f, 1f),
                                            right.coerceIn(0f, 1f),
                                            bottom.coerceIn(0f, 1f)
                                        ))
                                    }
                                }
                                dragStart = null
                                dragCurrent = null
                            },
                            onDragCancel = {
                                dragStart = null
                                dragCurrent = null
                            }
                        )
                    }
            ) {
                viewWidth = size.width
                viewHeight = size.height

                // Draw drag-in-progress rectangle
                val start = dragStart
                val current = dragCurrent
                if (start != null && current != null) {
                    val left = minOf(start.x, current.x)
                    val top = minOf(start.y, current.y)
                    val w = maxOf(start.x, current.x) - left
                    val h = maxOf(start.y, current.y) - top

                    drawRect(
                        color = Color.Cyan.copy(alpha = 0.15f),
                        topLeft = Offset(left, top),
                        size = Size(w, h)
                    )
                    drawRect(
                        color = Color.Cyan,
                        topLeft = Offset(left, top),
                        size = Size(w, h),
                        style = Stroke(
                            width = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                        )
                    )
                }

                // Draw confirmed selection
                selectedRegion?.let { region ->
                    val left = region.left * viewWidth
                    val top = region.top * viewHeight
                    val w = (region.right - region.left) * viewWidth
                    val h = (region.bottom - region.top) * viewHeight

                    // Fill
                    drawRect(
                        color = Color.Green.copy(alpha = 0.1f),
                        topLeft = Offset(left, top),
                        size = Size(w, h)
                    )
                    // Border
                    drawRect(
                        color = Color.Green,
                        topLeft = Offset(left, top),
                        size = Size(w, h),
                        style = Stroke(width = 2.5f)
                    )
                    // Corner markers
                    val cornerLen = 12f
                    val corners = listOf(
                        Offset(left, top),
                        Offset(left + w, top),
                        Offset(left, top + h),
                        Offset(left + w, top + h)
                    )
                    corners.forEach { corner ->
                        drawCircle(
                            color = Color.Green,
                            radius = 4f,
                            center = corner
                        )
                    }
                }
            }
        } else {
            // Not in selection mode — just draw existing selection (non-interactive)
            selectedRegion?.let { region ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val left = region.left * size.width
                    val top = region.top * size.height
                    val w = (region.right - region.left) * size.width
                    val h = (region.bottom - region.top) * size.height

                    drawRect(
                        color = Color.Green.copy(alpha = 0.08f),
                        topLeft = Offset(left, top),
                        size = Size(w, h)
                    )
                    drawRect(
                        color = Color.Green.copy(alpha = 0.6f),
                        topLeft = Offset(left, top),
                        size = Size(w, h),
                        style = Stroke(width = 2f)
                    )
                }
            }
        }

        // Selection mode indicator
        if (isSelectionMode) {
            SelectionModeIndicator(
                hasSelection = selectedRegion != null,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
            )
        }

        // Selected region info
        if (selectedRegion != null && !isSelectionMode) {
            SelectionInfoChip(
                region = selectedRegion,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun SelectionModeIndicator(
    hasSelection: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color.Cyan.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "SELECT MODE",
            color = Color.Black,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        if (hasSelection) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Tap to clear",
                color = Color.Black.copy(alpha = 0.6f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun SelectionInfoChip(
    region: RectF,
    modifier: Modifier = Modifier
) {
    val widthPct = ((region.right - region.left) * 100).toInt()
    val heightPct = ((region.bottom - region.top) * 100).toInt()

    Text(
        text = "Selected: ${widthPct}% x ${heightPct}% of frame",
        color = Color.Green,
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}
