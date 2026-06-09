package com.example.drones.ui

import android.app.Application
import android.graphics.RectF
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.drones.data.DroneState
import com.example.drones.data.SdkConnectionState
import com.example.drones.detection.DetectionResult
import com.example.drones.detection.LiveObjectDetector
import com.example.drones.localization.LiveMarkerDetector
import com.example.drones.localization.MarkerLayout
import com.example.drones.localization.MarkerObservation
import com.example.drones.localization.PathProjector
import com.example.drones.localization.TopScanLocalizer
import com.example.drones.orbit.CapturePhase
import com.example.drones.orbit.EditableRing
import com.example.drones.objectspec.ObjectConfigRepository
import com.example.drones.util.FileLogger
import com.example.drones.orbit.AutoYaw
import com.example.drones.orbit.MissionPlanner
import com.example.drones.orbit.OrbitExecutor
import com.example.drones.orbit.OrbitMission
import com.example.drones.orbit.OrbitState
import com.example.drones.orbit.VisualOrbitExecutor
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

    private lateinit var objectDetector: LiveObjectDetector
    private var markerDetector: LiveMarkerDetector? = null
    private var orbitExecutor: OrbitExecutor? = null
    private var autoCenter: com.example.drones.orbit.AutoCenterController? = null
    private var markerOrbit: com.example.drones.orbit.MarkerOrbitExecutor? = null
    private var detectionStatusJob: Job? = null

    init {
        objectDetector = LiveObjectDetector(application) { results ->
            _droneState.update { it.copy(
                detections = results,
                detectionModelLoaded = objectDetector.modelLoaded,
                detectionFramesReceived = objectDetector.framesReceived,
                detectionModelError = objectDetector.modelLoadError
            )}
        }
        observeSdkState()
        setupRecordingCallbacks()
        startDetectionStatusTicker()
    }

    /** Pushes model/frame status every 2s so the debug overlay stays current. */
    private fun startDetectionStatusTicker() {
        detectionStatusJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                _droneState.update { it.copy(
                    detectionModelLoaded = objectDetector.modelLoaded,
                    detectionFramesReceived = objectDetector.framesReceived,
                    detectionModelError = objectDetector.modelLoadError,
                    detectionDebugInfo = objectDetector.debugInfo
                )}
            }
        }
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
                    objectDetector.start()
                    com.example.drones.sdk.CameraController.applyBestVideoMode()  // 4K/60 to SD
                } else {
                    objectDetector.stop()
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
                is TelemetryUpdate.ForwardObstacle -> current.copy(
                    forwardObstacleDistM = update.distanceM
                )
                is TelemetryUpdate.LandingConfirmation -> current.copy(
                    isLandingConfirmationRequired = update.needed
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

    /**
     * Release Virtual Stick before any DJI-autopilot command (land / RTH).
     * DJI refuses auto-land / go-home while Virtual Stick owns the craft, which
     * is why landing sometimes did nothing after an orbit.
     */
    private fun releaseVirtualStick() {
        autoCenter?.abort(); autoCenter = null
        markerOrbit?.abort(); markerOrbit = null
        orbitExecutor?.abort(); orbitExecutor = null
        visualOrbit?.abort(); visualOrbit = null
        try { dji.v5.manager.aircraft.virtualstick.VirtualStickManager.getInstance().disableVirtualStick(null) }
        catch (_: Exception) {}
        FileLogger.write("Virtual stick released before land/RTH")
    }

    fun land() {
        releaseVirtualStick()
        _droneState.update { it.copy(isLanding = true, flightActionError = null,
            capturePhase = com.example.drones.orbit.CapturePhase.IDLE) }
        FlightController.land { success, error ->
            _droneState.update { it.copy(
                isLanding = if (!success) false else it.isLanding,
                flightActionError = if (!success) "Land failed: $error" else null
            )}
            if (!success) scheduleErrorClear()
        }
    }

    fun returnToHome() {
        releaseVirtualStick()
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
        _droneState.update { it.copy(selectedRegion = null, isSelectionMode = false, selectedDetectionId = null) }
    }

    // --- Detection + Orbit ---

    fun selectDetection(det: DetectionResult) {
        _droneState.update { it.copy(
            selectedDetectionId = det.trackId,
            selectedDetectionLabel = det.label,
            selectedDetectionBox = det.boxNorm
        )}
        Log.i(TAG, "Selected: ${det.label} (${det.trackId}), box=${det.boxNorm}")

        // Auto-yaw: rotate drone to face the tapped object (only if flying).
        if (_droneState.value.isFlying) {
            val xCenter = (det.boxNorm.left + det.boxNorm.right) / 2f
            AutoYaw.yawToBox(
                boxCenterXNorm = xCenter,
                currentHeading = _droneState.value.heading,
                getCurrentHeading = { _droneState.value.heading },
                onComplete = { achieved ->
                    Log.i(TAG, "AutoYaw complete: heading=$achieved°")
                }
            )
        }
    }

    /**
     * Tap-to-orbit: auto-yaw to face the tapped object, then auto-lock + start orbit.
     * Picks GPS orbit (outdoor) or visual orbit (indoor, sat=0).
     */
    fun tapToOrbit(det: DetectionResult, objectHeightM: Float = 1.0f) {
        if (!_droneState.value.isFlying) {
            _droneState.update { it.copy(flightActionError = "Drone must be flying first") }
            scheduleErrorClear()
            return
        }
        selectDetection(det)
        val useVisual = _droneState.value.satelliteCount < 6
        viewModelScope.launch {
            // Wait for auto-yaw to settle
            delay(2500)
            if (useVisual) startVisualOrbit(det, objectHeightM)
            else lockOrbitTarget(objectHeightM)
        }
    }

    private var visualOrbit: VisualOrbitExecutor? = null

    private fun startVisualOrbit(det: DetectionResult, objectHeightM: Float) {
        startVisualOrbitFromSelected(det.label, det.boxNorm, objectHeightM)
    }

    private fun startVisualOrbitFromSelected(label: String, box: android.graphics.RectF, objectHeightM: Float) {
        Log.i(TAG, "Starting visual orbit on '$label' (sat=${_droneState.value.satelliteCount})")
        _droneState.update { it.copy(orbitState = OrbitState.Arming) }
        visualOrbit?.abort()
        visualOrbit = VisualOrbitExecutor(
            targetLabel = label,
            initialBox = box,
            getDetections = { _droneState.value.detections },
            onState = { st -> _droneState.update { it.copy(orbitState = st) } }
        )
        visualOrbit?.start()
    }

    fun abortVisualOrbit() {
        visualOrbit?.abort()
        visualOrbit = null
    }

    /**
     * Lock the current drone position as orbit target.
     * Uses forward obstacle sensor for radius, heading for center direction.
     */
    fun lockOrbitTarget(objectHeightM: Float = 1.0f) {
        val state = _droneState.value

        // No GPS → fall back to visual orbit using selected detection
        if (state.satelliteCount < 6) {
            val selectedBox = state.selectedDetectionBox
            val selectedLabel = state.selectedDetectionLabel
            if (selectedBox == null || selectedLabel == null) {
                _droneState.update { it.copy(flightActionError = "Tap an object first (no GPS)") }
                scheduleErrorClear()
                return
            }
            startVisualOrbitFromSelected(selectedLabel, selectedBox, objectHeightM)
            return
        }

        val sensorDist = state.forwardObstacleDistM
        if (sensorDist < 0.38f || sensorDist > 20f) {
            _droneState.update { it.copy(flightActionError =
                if (sensorDist < 0.38f) "Too close — move back (min 0.4m)"
                else "Out of sensor range — move within 20m") }
            scheduleErrorClear()
            return
        }

        val (cLat, cLon, cAlt) = MissionPlanner.computeCenter(
            state.latitude, state.longitude,
            state.heading, sensorDist.toDouble(), objectHeightM.toDouble()
        )
        val startAngle = MissionPlanner.startAngleDeg(state.heading)

        val mission = OrbitMission(
            centerLat = cLat,
            centerLon = cLon,
            centerAlt = cAlt,
            orbitRadius = sensorDist.toDouble(),
            objectHeight = objectHeightM.toDouble(),
            lockAlt = state.altitude,
            startAngleDeg = startAngle
        )
        val rings = MissionPlanner.executionOrder(MissionPlanner.planRings(mission))

        Log.i(TAG, "Orbit locked: radius=${sensorDist}m center=($cLat,$cLon) rings=${rings.size}")
        _droneState.update { it.copy(
            orbitLockedRadius = sensorDist,
            orbitObjectHeight = objectHeightM,
            orbitState = OrbitState.Arming
        )}

        launchOrbit(mission, rings)
    }

    /** Shared executor launch — used by GPS lock and marker orbit. */
    private fun launchOrbit(mission: OrbitMission, rings: List<com.example.drones.orbit.OrbitRing>) {
        orbitExecutor?.abort()
        orbitExecutor = OrbitExecutor(
            mission = mission,
            rings = rings,
            getForwardObstacleDist = { _droneState.value.forwardObstacleDistM },
            getDroneAlt = { _droneState.value.altitude },
            onState = { orbitState ->
                _droneState.update { it.copy(orbitState = orbitState) }
                if (orbitState is OrbitState.Flying && !_droneState.value.isRecordingOnDevice) {
                    startRecording()  // auto-start recording when orbit begins
                }
                if (orbitState == OrbitState.Done || orbitState == OrbitState.Aborted) {
                    stopRecording()
                    orbitExecutor = null
                }
            }
        )
        orbitExecutor?.start()
    }

    fun abortOrbit() {
        orbitExecutor?.abort()
        orbitExecutor = null
        visualOrbit?.abort()
        visualOrbit = null
        AutoYaw.cancel()
        _droneState.update { it.copy(orbitState = OrbitState.Idle) }
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
        detectionStatusJob?.cancel()
        objectDetector.stop()
        markerDetector?.stop()
        orbitExecutor?.abort()
        stopTelemetry()
        recordingManager.cleanup()
        stopRecordingTimer()
        gimbalLockJob?.cancel()
        errorClearJob?.cancel()
    }

    /**
     * Toggle survey (ArUco marker) mode. Mutually exclusive with object detection
     * since both share a single DJI frame listener.
     */
    private var markerLayout: MarkerLayout? = null
    private var surveyObjectHeightM: Double = 0.30   // from CAD ObjectConfig if present

    fun toggleSurveyMode() {
        val on = !_droneState.value.surveyMode
        _droneState.update { it.copy(
            surveyMode = on,
            markersDetected = emptyList(),
            topScan = null,
            previewPath = null,
            editableRings = emptyList(),
            selectedRingIndex = 0,
            capturePhase = if (on) CapturePhase.SCANNING else CapturePhase.IDLE
        )}
        if (on) {
            objectDetector.stop()
            markerLayout = MarkerLayout.load(getApplication(), "default")
            surveyObjectHeightM = ObjectConfigRepository.list(getApplication()).firstOrNull()
                ?.let { ObjectConfigRepository.load(getApplication(), it.id)?.heightM } ?: 0.30
            if (markerDetector == null) {
                markerDetector = LiveMarkerDetector(
                    context = getApplication(),
                    onMarkers = { obs -> onMarkersFrame(obs) }
                )
            }
            markerDetector?.start()
            setGimbalPitch(-90.0)   // point straight down to see ground markers
            Log.i(TAG, "Survey mode ON — gimbal -90°")
        } else {
            markerDetector?.stop()
            objectDetector.start()
            setGimbalPitch(0.0)
            Log.i(TAG, "Survey mode OFF")
        }
    }

    /** Per-frame marker handling: live center, phase advance, AR preview. */
    private fun onMarkersFrame(obs: List<MarkerObservation>) {
        val st = _droneState.value
        val scan = TopScanLocalizer.localize(obs, markerLayout ?: MarkerLayout.default())

        // Phase advance: SCANNING -> CENTER_READY once >=4 markers give a center.
        val phase = when {
            st.capturePhase == CapturePhase.SCANNING && (scan?.markers?.size ?: 0) >= 4 -> CapturePhase.CENTER_READY
            st.capturePhase == CapturePhase.CENTER_READY && (scan?.markers?.size ?: 0) < 4 -> CapturePhase.SCANNING
            else -> st.capturePhase
        }

        // AR preview: editable rings while editing, else the planned rings.
        val preview = if (scan != null) {
            if (phase == CapturePhase.EDITING && st.editableRings.isNotEmpty()) {
                PathProjector.projectEditable(obs, scan, st.editableRings, st.selectedRingIndex)
            } else {
                PathProjector.project(obs, scan,
                    com.example.drones.orbit.MarkerPathPlanner.planRings(
                        scan, surveyObjectHeightM, markerLayout?.tableHeightM ?: 0.0))
            }
        } else null

        _droneState.update { it.copy(markersDetected = obs, topScan = scan, previewPath = preview, capturePhase = phase) }
    }

    /** Live center offset (meters, body right/forward) for AutoCenterController. */
    private fun centerOffset(): Pair<Double, Double>? {
        val scan = _droneState.value.topScan ?: return null
        // camera-frame center x,y ≈ horizontal offset of center from drone (gimbal down)
        return Pair(scan.centerXM, scan.centerYM)
    }

    // --- Auto-center: drone flies itself over the computed center ---
    fun startAutoCenter() {
        if (!_droneState.value.isFlying) {
            _droneState.update { it.copy(flightActionError = "Drone must be flying first") }
            scheduleErrorClear(); return
        }
        if (_droneState.value.topScan == null) {
            _droneState.update { it.copy(flightActionError = "No center yet") }
            scheduleErrorClear(); return
        }
        _droneState.update { it.copy(capturePhase = CapturePhase.CENTERING) }
        autoCenter?.abort()
        autoCenter = com.example.drones.orbit.AutoCenterController(
            getOffsetM = { centerOffset() },
            onSettled = {
                viewModelScope.launch {
                    val scan = _droneState.value.topScan
                    val rings = if (scan != null)
                        com.example.drones.orbit.MarkerPathPlanner.planEditableRings(
                            scan, surveyObjectHeightM, markerLayout?.tableHeightM ?: 0.0)
                    else emptyList()
                    _droneState.update { it.copy(
                        capturePhase = CapturePhase.EDITING,
                        editableRings = rings,
                        selectedRingIndex = 0
                    )}
                }
            },
            onState = { msg -> FileLogger.write("AutoCenter: $msg") }
        )
        autoCenter?.start()
    }

    fun abortAutoCenter() {
        autoCenter?.abort(); autoCenter = null
        _droneState.update { it.copy(capturePhase = CapturePhase.CENTER_READY) }
    }

    // --- Per-ring editing ---
    fun selectRing(i: Int) = _droneState.update { it.copy(selectedRingIndex = i) }

    fun updateRingAxes(i: Int, semiMajorM: Double, semiMinorM: Double) = editRing(i) {
        it.copy(semiMajorM = semiMajorM.coerceIn(0.3, 8.0), semiMinorM = semiMinorM.coerceIn(0.3, 8.0))
    }
    fun nudgeRing(i: Int, dMajor: Double, dMinor: Double) = editRing(i) {
        it.copy(semiMajorM = (it.semiMajorM + dMajor).coerceIn(0.3, 8.0),
                semiMinorM = (it.semiMinorM + dMinor).coerceIn(0.3, 8.0))
    }
    fun updateRingHeight(i: Int, h: Double) = editRing(i) { it.copy(heightAboveFloorM = h.coerceIn(0.3, 6.0)) }
    fun lockRing(i: Int) = editRing(i) { it.copy(locked = true) }
    fun unlockRing(i: Int) = editRing(i) { it.copy(locked = false) }

    private fun editRing(i: Int, change: (EditableRing) -> EditableRing) {
        _droneState.update { st ->
            if (i !in st.editableRings.indices) return@update st
            val updated = st.editableRings.toMutableList().also { it[i] = change(it[i]) }
            st.copy(editableRings = updated)
        }
    }

    val allRingsLocked: Boolean
        get() = _droneState.value.editableRings.let { it.isNotEmpty() && it.all { r -> r.locked } }

    // --- Start orbit (after all rings locked) ---
    fun requestMarkerOrbitConfirm() {
        if (!allRingsLocked) {
            _droneState.update { it.copy(flightActionError = "Lock all rings first") }
            scheduleErrorClear(); return
        }
        _droneState.update { it.copy(pendingOrbitConfirm = true) }
    }

    fun cancelMarkerOrbit() = _droneState.update { it.copy(pendingOrbitConfirm = false) }

    fun confirmMarkerOrbit() {
        _droneState.update { it.copy(pendingOrbitConfirm = false) }
        if (!_droneState.value.isFlying) {
            _droneState.update { it.copy(flightActionError = "Drone must be flying first") }
            scheduleErrorClear(); return
        }
        val rings = _droneState.value.editableRings
        if (rings.isEmpty()) return

        // Persist the planned path (marker-local, DJI-style waypoints) before flying.
        _droneState.value.topScan?.let { scan ->
            val wps = com.example.drones.waypoints.WaypointBuilder.build(scan, rings)
            val path = com.example.drones.waypoints.WaypointPath(
                id = com.example.drones.waypoints.WaypointStore.newId(),
                createdUtc = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .format(java.util.Date()),
                centerXM = scan.centerXM, centerYM = scan.centerYM, waypoints = wps
            )
            com.example.drones.waypoints.WaypointStore.save(getApplication(), path)
        }

        _droneState.update { it.copy(capturePhase = CapturePhase.ORBITING) }
        FileLogger.write("MarkerOrbit start: ${rings.size} rings")
        markerOrbit?.abort()
        markerOrbit = com.example.drones.orbit.MarkerOrbitExecutor(
            rings = rings,
            getDroneAlt = { _droneState.value.altitude },
            isFlying = { _droneState.value.isFlying },
            getCenterOffsetM = { centerOffset() },
            onState = { orbitState ->
                _droneState.update { it.copy(orbitState = orbitState) }
                if (orbitState is OrbitState.Flying && !_droneState.value.isRecordingOnDevice) startRecording()
                if (orbitState == OrbitState.Done || orbitState == OrbitState.Aborted ||
                    orbitState is OrbitState.Error) {
                    stopRecording()
                    markerOrbit = null
                    _droneState.update { it.copy(capturePhase = CapturePhase.EDITING) }
                }
            }
        )
        markerOrbit?.start()
    }

    fun abortMarkerOrbit() {
        markerOrbit?.abort(); markerOrbit = null
        _droneState.update { it.copy(capturePhase = CapturePhase.EDITING) }
    }
}
