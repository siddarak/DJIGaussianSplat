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
import com.example.drones.sdk.FlightController
import com.example.drones.sdk.GimbalController
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
        private const val FLIGHT_ERROR_CLEAR_MS = 4000L
    }

    private val _droneState = MutableStateFlow(DroneState())
    val droneState: StateFlow<DroneState> = _droneState.asStateFlow()

    private var telemetryManager: TelemetryManager? = null
    private val recordingManager = RecordingManager(application)
    private var recordingTimerJob: Job? = null
    private var errorClearJob: Job? = null
    private var gimbalLockJob: Job? = null

    init {
        observeSdkState()
        setupRecordingCallbacks()
    }

    // --- SDK state observation ---

    private fun observeSdkState() {
        viewModelScope.launch {
            DjiSdkManager.connectionState.collect { sdkState ->
                _droneState.update { it.copy(
                    sdkRegistered = sdkState == SdkConnectionState.REGISTERED
                            || sdkState == SdkConnectionState.PRODUCT_CONNECTED,
                    errorMessage = if (sdkState == SdkConnectionState.ERROR)
                        DjiSdkManager.errorMessage.value else null
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
                    if (recordingManager.isRecording) stopRecording()
                    gimbalLockJob?.cancel()
                    gimbalLockJob = null
                    // Reset flight action states on disconnect
                    _droneState.update { it.copy(
                        isTakingOff = false,
                        isLanding = false,
                        isRth = false,
                        isLandingConfirmationRequired = false,
                        gimbalLocked = false
                    )}
                }
            }
        }
    }

    // --- Telemetry ---

    private fun startTelemetry() {
        if (telemetryManager != null) return
        telemetryManager = TelemetryManager { update -> handleTelemetryUpdate(update) }
        telemetryManager?.startListening()
        Log.i(TAG, "Telemetry started")
    }

    private fun stopTelemetry() {
        telemetryManager?.stopListening()
        telemetryManager = null
        _droneState.update { it.copy(
            altitude = 0.0, latitude = 0.0, longitude = 0.0,
            speedHorizontal = 0.0, speedVertical = 0.0, heading = 0.0,
            satelliteCount = 0, isFlying = false, flightMode = "N/A",
            batteryPercent = 0, batteryTemperature = 0.0, signalQuality = 0,
            gimbalPitch = 0.0, gimbalYaw = 0.0, gimbalRoll = 0.0,
            lowBatteryWarning = false, criticalBatteryWarning = false
        )}
        Log.i(TAG, "Telemetry stopped")
    }

    private fun handleTelemetryUpdate(update: TelemetryUpdate) {
        _droneState.update { current ->
            when (update) {
                is TelemetryUpdate.Altitude      -> current.copy(altitude = update.meters)
                is TelemetryUpdate.Location      -> current.copy(
                    latitude = update.latitude,
                    longitude = update.longitude,
                    altitude = update.altitude
                )
                is TelemetryUpdate.Velocity      -> current.copy(
                    speedHorizontal = update.horizontal,
                    speedVertical = update.vertical
                )
                is TelemetryUpdate.Heading       -> current.copy(heading = update.degrees)
                is TelemetryUpdate.FlyingState   -> current.copy(
                    isFlying = update.isFlying,
                    // Clear takeoff/landing pending states once we know airborne status
                    isTakingOff = if (update.isFlying) false else current.isTakingOff,
                    isLanding   = if (!update.isFlying) false else current.isLanding
                )
                is TelemetryUpdate.FlightModeUpdate -> current.copy(
                    flightMode = update.mode,
                    isRth = update.mode.contains("GO_HOME", ignoreCase = true)
                )
                is TelemetryUpdate.Satellites    -> current.copy(satelliteCount = update.count)
                is TelemetryUpdate.Battery       -> current.copy(
                    batteryPercent = update.percent,
                    lowBatteryWarning = update.lowWarning,
                    criticalBatteryWarning = update.criticalWarning
                )
                is TelemetryUpdate.BatteryTemp   -> current.copy(batteryTemperature = update.celsius)
                is TelemetryUpdate.GimbalAttitude -> current.copy(
                    gimbalPitch = update.pitch,
                    gimbalYaw = update.yaw,
                    gimbalRoll = update.roll
                )
                is TelemetryUpdate.Signal        -> current.copy(
                    signalQuality = update.quality,
                    signalLost = update.quality < 10 && current.productConnected
                )
            }
        }
    }

    // --- Flight controls ---

    fun takeOff() {
        _droneState.update { it.copy(isTakingOff = true, flightActionError = null) }
        FlightController.takeOff { success, error ->
            _droneState.update { it.copy(
                isTakingOff = if (!success) false else it.isTakingOff,
                flightActionError = if (!success) "Takeoff failed: $error" else null
            )}
            if (!success) scheduleErrorClear()
        }
    }

    fun land() {
        _droneState.update { it.copy(isLanding = true, flightActionError = null) }
        FlightController.land { success, error ->
            _droneState.update { it.copy(
                isLanding = if (!success) false else it.isLanding,
                flightActionError = if (!success) "Land failed: $error" else null
            )}
            if (!success) scheduleErrorClear()
        }
    }

    fun returnToHome() {
        _droneState.update { it.copy(isRth = true, flightActionError = null) }
        FlightController.returnToHome { success, error ->
            _droneState.update { it.copy(
                isRth = if (!success) false else it.isRth,
                flightActionError = if (!success) "RTH failed: $error" else null
            )}
            if (!success) scheduleErrorClear()
        }
    }

    fun cancelRth() {
        FlightController.cancelReturnToHome { success, error ->
            _droneState.update { it.copy(
                isRth = if (success) false else it.isRth,
                flightActionError = if (!success) "Cancel RTH failed: $error" else null
            )}
            if (!success) scheduleErrorClear()
        }
    }

    fun confirmLanding() {
        FlightController.confirmLanding { success, error ->
            if (!success) {
                _droneState.update { it.copy(flightActionError = "Confirm land failed: $error") }
                scheduleErrorClear()
            }
        }
    }

    fun emergencyStop() {
        FlightController.emergencyStop { success, error ->
            if (!success) {
                _droneState.update { it.copy(flightActionError = "Emergency stop failed: $error") }
                scheduleErrorClear()
            }
        }
    }

    // --- Gimbal ---

    fun lockGimbal() {
        val currentPitch = _droneState.value.gimbalPitch
        _droneState.update { it.copy(gimbalLocked = true, gimbalLockAngle = currentPitch) }
        startGimbalLockEnforcement(currentPitch)
    }

    fun unlockGimbal() {
        gimbalLockJob?.cancel()
        gimbalLockJob = null
        _droneState.update { it.copy(gimbalLocked = false) }
    }

    /**
     * Continuously re-sends the gimbal angle command every 500ms to keep it locked.
     * One-shot commands don't hold — the RC scroll wheel or vibration drifts the gimbal.
     */
    private fun startGimbalLockEnforcement(targetPitch: Double) {
        gimbalLockJob?.cancel()
        gimbalLockJob = viewModelScope.launch {
            while (true) {
                GimbalController.setPitch(targetPitch, durationSec = 0.0) { success, error ->
                    if (!success) {
                        Log.w(TAG, "Gimbal lock enforcement failed: $error")
                    }
                }
                delay(500)
            }
        }
    }

    fun setGimbalPitch(degrees: Double) {
        GimbalController.setPitch(degrees) { success, error ->
            if (!success) {
                _droneState.update { it.copy(flightActionError = "Gimbal move failed: $error") }
                scheduleErrorClear()
            }
        }
    }

    fun resetGimbal() {
        GimbalController.resetToLevel { success, error ->
            if (success) {
                _droneState.update { it.copy(gimbalLocked = false) }
            } else {
                _droneState.update { it.copy(flightActionError = "Gimbal reset failed: $error") }
                scheduleErrorClear()
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
        // Listen for the physical record button on the RC controller
        recordingManager.listenForControllerRecordButton(
            onStartRequested = { startRecording() },
            onStopRequested = { stopRecording() }
        )
    }

    fun toggleDebugOverlay() {
        _droneState.update { it.copy(showDebugOverlay = !it.showDebugOverlay) }
    }

    fun startRecording() {
        recordingManager.startBothRecording()
        startRecordingTimer()
    }

    fun stopRecording() {
        recordingManager.stopBothRecording()
        stopRecordingTimer()
        _droneState.update { it.copy(
            recordingTimeSeconds = 0,
            lastRecordingPath = recordingManager.lastRecordingPath
        )}
    }

    fun getLastRecordingPath(): String = recordingManager.lastRecordingPath
    fun getLastRecordingUri(): android.net.Uri? = recordingManager.lastRecordingUri

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            var seconds = 0
            while (true) {
                _droneState.update { it.copy(recordingTimeSeconds = seconds++) }
                delay(1000)
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
    }

    // --- Object Selection ---

    fun toggleSelectionMode() {
        _droneState.update { it.copy(isSelectionMode = !it.isSelectionMode) }
    }

    fun selectRegion(region: RectF?) {
        _droneState.update { it.copy(selectedRegion = region) }
    }

    fun clearSelection() {
        _droneState.update { it.copy(selectedRegion = null, isSelectionMode = false) }
    }

    // --- Helpers ---

    /** Auto-dismiss flight action errors after a few seconds */
    private fun scheduleErrorClear() {
        errorClearJob?.cancel()
        errorClearJob = viewModelScope.launch {
            delay(FLIGHT_ERROR_CLEAR_MS)
            _droneState.update { it.copy(flightActionError = null) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTelemetry()
        recordingManager.cleanup()
        stopRecordingTimer()
        gimbalLockJob?.cancel()
        errorClearJob?.cancel()
    }
}
