package com.example.drones.ui

import android.app.Application
import android.graphics.RectF
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.drones.data.DroneState
import com.example.drones.data.SdkConnectionState
import com.example.drones.recording.RecordingManager
import com.example.drones.sdk.DjiSdkManager
import com.example.drones.sdk.TelemetryManager
import com.example.drones.sdk.TelemetryUpdate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _droneState = MutableStateFlow(DroneState())
    val droneState: StateFlow<DroneState> = _droneState.asStateFlow()

    private var telemetryManager: TelemetryManager? = null
    private val recordingManager = RecordingManager(application)
    private var recordingTimerJob: Job? = null

    init {
        observeSdkState()
        setupRecordingCallbacks()
    }

    private fun observeSdkState() {
        viewModelScope.launch {
            DjiSdkManager.connectionState.collect { state ->
                _droneState.update { it.copy(
                    sdkRegistered = state == SdkConnectionState.REGISTERED
                            || state == SdkConnectionState.PRODUCT_CONNECTED,
                    errorMessage = if (state == SdkConnectionState.ERROR) {
                        DjiSdkManager.errorMessage.value
                    } else null
                )}
            }
        }
        viewModelScope.launch {
            DjiSdkManager.productConnected.collect { connected ->
                _droneState.update { it.copy(productConnected = connected) }
                if (connected) {
                    startTelemetry()
                } else {
                    stopTelemetry()
                    // Safety: stop recording if drone disconnects
                    if (recordingManager.isRecording) {
                        stopRecording()
                    }
                }
            }
        }
    }

    // --- Telemetry ---

    private fun startTelemetry() {
        if (telemetryManager != null) return
        Log.i(TAG, "Starting telemetry manager")
        telemetryManager = TelemetryManager { update ->
            handleTelemetryUpdate(update)
        }
        telemetryManager?.startListening()
    }

    private fun stopTelemetry() {
        Log.i(TAG, "Stopping telemetry manager")
        telemetryManager?.stopListening()
        telemetryManager = null
        _droneState.update { it.copy(
            altitude = 0.0,
            latitude = 0.0,
            longitude = 0.0,
            speedHorizontal = 0.0,
            speedVertical = 0.0,
            heading = 0.0,
            satelliteCount = 0,
            isFlying = false,
            flightMode = "N/A",
            batteryPercent = 0,
            batteryTemperature = 0.0,
            signalQuality = 0,
            gimbalPitch = 0.0,
            gimbalYaw = 0.0,
            gimbalRoll = 0.0,
            lowBatteryWarning = false,
            criticalBatteryWarning = false,
        )}
    }

    private fun handleTelemetryUpdate(update: TelemetryUpdate) {
        _droneState.update { current ->
            when (update) {
                is TelemetryUpdate.Altitude -> current.copy(altitude = update.meters)
                is TelemetryUpdate.Location -> current.copy(
                    latitude = update.latitude,
                    longitude = update.longitude,
                    altitude = update.altitude
                )
                is TelemetryUpdate.Velocity -> current.copy(
                    speedHorizontal = update.horizontal,
                    speedVertical = update.vertical
                )
                is TelemetryUpdate.Heading -> current.copy(heading = update.degrees)
                is TelemetryUpdate.FlyingState -> current.copy(isFlying = update.isFlying)
                is TelemetryUpdate.FlightModeUpdate -> current.copy(flightMode = update.mode)
                is TelemetryUpdate.Satellites -> current.copy(satelliteCount = update.count)
                is TelemetryUpdate.Battery -> current.copy(
                    batteryPercent = update.percent,
                    lowBatteryWarning = update.lowWarning,
                    criticalBatteryWarning = update.criticalWarning
                )
                is TelemetryUpdate.BatteryTemp -> current.copy(batteryTemperature = update.celsius)
                is TelemetryUpdate.GimbalAttitude -> current.copy(
                    gimbalPitch = update.pitch,
                    gimbalYaw = update.yaw,
                    gimbalRoll = update.roll
                )
                is TelemetryUpdate.Signal -> current.copy(
                    signalQuality = update.quality,
                    signalLost = update.quality < 10 && current.productConnected
                )
            }
        }
    }

    // --- Recording ---

    private fun setupRecordingCallbacks() {
        recordingManager.onStateChanged = { onDevice, onDrone, filePath ->
            _droneState.update { it.copy(
                isRecordingOnDevice = onDevice,
                isRecordingOnDrone = onDrone,
                onDeviceFilePath = filePath
            )}
        }
    }

    fun startRecording() {
        Log.i(TAG, "Starting both recordings")
        recordingManager.startBothRecording()
        startRecordingTimer()
    }

    fun stopRecording() {
        Log.i(TAG, "Stopping both recordings")
        recordingManager.stopBothRecording()
        stopRecordingTimer()
        _droneState.update { it.copy(
            recordingTimeSeconds = 0,
            lastRecordingPath = recordingManager.lastRecordingPath
        )}
    }

    fun getLastRecordingUri(): android.net.Uri? = recordingManager.lastRecordingUri
    fun getLastRecordingPath(): String = recordingManager.lastRecordingPath

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            var seconds = 0
            while (true) {
                _droneState.update { it.copy(recordingTimeSeconds = seconds) }
                delay(1000)
                seconds++
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
    }

    // --- Object Selection (Layer 2 foundation) ---

    fun toggleSelectionMode() {
        _droneState.update { it.copy(isSelectionMode = !it.isSelectionMode) }
    }

    fun selectRegion(region: RectF?) {
        _droneState.update { it.copy(selectedRegion = region) }
    }

    fun clearSelection() {
        _droneState.update { it.copy(selectedRegion = null, isSelectionMode = false) }
    }

    override fun onCleared() {
        super.onCleared()
        stopTelemetry()
        recordingManager.cleanup()
        stopRecordingTimer()
        Log.d(TAG, "ViewModel cleared")
    }
}
