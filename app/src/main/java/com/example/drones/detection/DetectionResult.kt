package com.example.drones.detection

import android.graphics.RectF

/**
 * One detected object from a single inference pass.
 *
 * @param label      Human-readable COCO class name (e.g. "person", "car")
 * @param confidence Score 0–1
 * @param boxNorm    Bounding box in NORMALIZED coordinates (0–1 relative to frame size)
 *                   so the overlay can scale it to any view size
 * @param trackId    Stable ID across frames (index-based, resets each frame)
 */
data class DetectionResult(
    val label: String,
    val confidence: Float,
    val boxNorm: RectF,
    val trackId: Int
)
