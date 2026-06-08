package com.example.drones.orbit

/**
 * One user-editable capture ring. Circle when semiMajor == semiMinor.
 * Axes + height are edited in the per-ring editor; locked individually.
 *
 * Frame: ground plane around the scan center (meters). Z = height above floor.
 */
data class EditableRing(
    val index: Int,
    val semiMajorM: Double,        // a — along world X
    val semiMinorM: Double,        // b — along world Y
    val heightAboveFloorM: Double,
    val gimbalPitchDeg: Double,
    val locked: Boolean = false
)

/** Capture workflow phases for the marker-based indoor flow. */
enum class CapturePhase {
    IDLE,          // survey off
    SCANNING,      // markers detected, center computing live (< 4 markers)
    CENTER_READY,  // >= 4 markers, center found, auto-center offered
    CENTERING,     // drone flying itself over the center
    EDITING,       // hovering over center, editing/locking rings
    READY,         // all rings locked, awaiting orbit confirm
    ORBITING       // flying the rings
}
