package com.example.drones.data

import android.graphics.RectF

data class DroneState(
    // Connection
    val sdkRegistered: Boolean = false,
    val productConnected: Boolean = false,
    val rcConnected: Boolean = false,

    // Telemetry
    val altitude: Double = 0.0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val speedHorizontal: Double = 0.0,
    val speedVertical: Double = 0.0,
    val heading: Double = 0.0,
    val satelliteCount: Int = 0,
    val isFlying: Boolean = false,
    val flightMode: String = "N/A",

    // Battery
    val batteryPercent: Int = 0,
    val batteryTemperature: Double = 0.0,

    // Signal
    val signalQuality: Int = 0,

    // Gimbal
    val gimbalPitch: Double = 0.0,
    val gimbalYaw: Double = 0.0,
    val gimbalRoll: Double = 0.0,

    // Recording
    val isRecordingOnDevice: Boolean = false,
    val isRecordingOnDrone: Boolean = false,
    val recordingTimeSeconds: Int = 0,
    val onDeviceFilePath: String = "",
    val lastRecordingPath: String = "",

    // Warnings
    val lowBatteryWarning: Boolean = false,
    val criticalBatteryWarning: Boolean = false,
    val isFailsafe: Boolean = false,
    val signalLost: Boolean = false,

    // Object Selection (Layer 2 foundation)
    val selectedRegion: RectF? = null,
    val isSelectionMode: Boolean = false,

    // Error
    val errorMessage: String? = null
)

enum class SdkConnectionState {
    INITIALIZING,
    REGISTERING,
    REGISTERED,
    PRODUCT_CONNECTED,
    DISCONNECTED,
    ERROR
}
