package com.example.drones.data

import android.graphics.RectF
import com.example.drones.detection.DetectionResult
import com.example.drones.orbit.OrbitState

data class DroneState(
    // --- Connection ---
    val sdkRegistered: Boolean = false,
    val productConnected: Boolean = false,
    val rcConnected: Boolean = false,

    // --- Telemetry ---
    val altitude: Double = 0.0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val speedHorizontal: Double = 0.0,
    val speedVertical: Double = 0.0,
    val heading: Double = 0.0,
    val satelliteCount: Int = 0,
    val isFlying: Boolean = false,
    val flightMode: String = "N/A",

    // --- Battery ---
    val batteryPercent: Int = 0,
    val batteryTemperature: Double = 0.0,

    // --- Signal ---
    val signalQuality: Int = 0,

    // --- Gimbal ---
    val gimbalPitch: Double = 0.0,
    val gimbalYaw: Double = 0.0,
    val gimbalRoll: Double = 0.0,
    val gimbalLocked: Boolean = false,
    val gimbalLockAngle: Double = 0.0,       // angle stored when lock was pressed

    // --- Flight Actions ---
    val isTakingOff: Boolean = false,        // takeoff command sent, waiting for airborne
    val isLanding: Boolean = false,          // landing command sent, descending
    val isRth: Boolean = false,              // RTH active
    val isLandingConfirmationRequired: Boolean = false,  // waiting for confirm-land

    // --- Recording ---
    val isRecordingOnDevice: Boolean = false,
    val isRecordingOnDrone: Boolean = false,
    val recordingTimeSeconds: Int = 0,
    val onDeviceFilePath: String = "",
    val lastRecordingPath: String = "",

    // --- Warnings ---
    val lowBatteryWarning: Boolean = false,
    val criticalBatteryWarning: Boolean = false,
    val isFailsafe: Boolean = false,
    val signalLost: Boolean = false,

    // --- Object Detection ---
    val detections: List<DetectionResult> = emptyList(),
    val selectedDetectionId: Int? = null,
    val detectionModelLoaded: Boolean = false,
    val detectionFramesReceived: Long = 0L,

    // --- Object Selection ---
    val selectedRegion: RectF? = null,
    val isSelectionMode: Boolean = false,

    // --- Obstacle sensor ---
    val forwardObstacleDistM: Float = 0f,   // 0 = no reading / out of range

    // --- Orbit mission ---
    val orbitState: OrbitState = OrbitState.Idle,
    val orbitLockedRadius: Float = 0f,      // sensor reading at lock time
    val orbitObjectHeight: Float = 1.0f,    // user estimate in meters

    // --- Debug ---
    val showDebugOverlay: Boolean = false,

    // --- Error ---
    val errorMessage: String? = null,
    val flightActionError: String? = null    // transient error from last flight command
)

enum class SdkConnectionState {
    INITIALIZING,
    REGISTERING,
    REGISTERED,
    PRODUCT_CONNECTED,
    DISCONNECTED,
    ERROR
}
