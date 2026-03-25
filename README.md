# DJI Gaussian Splat Capture App

Android companion app for DJI Mini 4 Pro (RC-N3) — captures video for 3D Gaussian Splatting reconstruction.

## Architecture

```
com.example.drones/
├── DronesApplication.kt          # App entry, DJI SDK init via DJIHelperService
├── MainActivity.kt               # Single activity, Compose UI, permissions
├── data/
│   └── DroneState.kt             # Single state data class for entire UI + SdkConnectionState enum
├── recording/
│   ├── RecordingManager.kt       # On-device H.265 muxing + drone SD card recording
│   ├── Mp4Muxer.kt               # Thread-safe MediaMuxer wrapper (HEVC/H.265)
│   └── RecordingDebugLog.kt      # In-app debug log (on-screen, no ADB needed)
├── sdk/
│   ├── DjiSdkManager.kt          # SDK registration, connection state flows
│   ├── FlightController.kt       # Takeoff/land/RTH/emergency via KeyManager
│   ├── GimbalController.kt       # Gimbal pitch/lock/reset via KeyManager
│   ├── TelemetryManager.kt       # All telemetry listeners → TelemetryUpdate sealed class
│   └── VideoStreamManager.kt     # Camera stream surface + raw stream listener management
└── ui/
    ├── MainViewModel.kt          # Orchestrates everything, holds DroneState flow
    ├── screens/
    │   └── FlightScreen.kt       # Main screen layout, debug overlay toggle
    ├── components/
    │   ├── TopHudBar.kt          # SDK/Link/Mode/Signal/Battery status bar
    │   ├── BottomTelemetryBar.kt # ALT/H.SPD/V.SPD/HDG/SAT/GIMBAL telemetry
    │   ├── WarningBanners.kt     # Animated battery/signal/disconnect alerts
    │   ├── FlightControlsPanel.kt# Left panel: takeoff/land/RTH/gimbal controls
    │   ├── RecordingControls.kt  # Right panel: record button, timer, status dots
    │   ├── RecordingDebugOverlay.kt # On-screen recording pipeline debug log
    │   ├── ObjectSelector.kt     # Bounding box selection overlay (normalized 0-1 coords)
    │   └── VideoFeedView.kt      # SurfaceView for decoded video display
    └── theme/
        ├── Color.kt
        └── Theme.kt
```

## Key Technical Details

### DJI MSDK V5
- SDK version: 5.17.0
- Drone: DJI Mini 4 Pro
- Controller: RC-N3 (USB accessory mode)
- All flight/gimbal/camera commands use `KeyManager.performAction()` with `KeyTools.createKey()`
- Connection state tracked via `DjiSdkManager.connectionState` and `DjiSdkManager.productConnected` StateFlows

### Video Pipeline
- Mini 4 Pro streams **H.265 (HEVC)**, not H.264
- Raw encoded stream via `ICameraStreamManager.addReceiveStreamListener()`
- Decoded video to Surface via `ICameraStreamManager.putCameraStreamSurface()`
- `setKeepAliveDecoding(true)` required for raw stream to flow

### On-Device Recording Pipeline
1. `RecordingManager.startOnDeviceRecording()` creates output file + attaches raw stream listener
2. Raw H.265 NAL units arrive via `ReceiveStreamListener` callback (MSDK thread)
3. NAL type parsed: `(byte[offset+4] >> 1) & 0x3F` for 4-byte start codes
4. VPS (type 32), SPS (type 33), PPS (type 34) collected first
5. Once all three found → `Mp4Muxer.configureHevcCsd()` concatenates VPS+SPS+PPS into single `csd-0` with start codes
6. IDR keyframes (type 19, 20) and regular frames written via `Mp4Muxer.writeNalUnit()`
7. On stop: file copied to MediaStore (`Movies/DroneCaptures`) for Gallery visibility

### Recording Debug System
- `RecordingDebugLog` singleton with `lines: StateFlow<List<String>>` and `status: StateFlow<RecordingDebugStatus>`
- Shows on-screen via DEBUG button (bottom-right of flight screen)
- Tracks: stream listener active, raw frame count, VPS/SPS/PPS found, muxer started, frame count, file size, errors
- Color-coded: red=error, orange=warning, green=success

### Gimbal Lock
- `lockGimbal()` starts a coroutine that re-sends `setPitch()` every 500ms
- One-shot commands don't hold — RC scroll wheel or vibration drifts the gimbal
- `unlockGimbal()` cancels the enforcement coroutine

### Controller Record Button
- `KeyIsRecording` listener on `DJICameraKey` syncs physical RC button with app recording
- Press record on controller → starts both on-device + drone SD recording

### Known Issues / Current Investigation
- **On-device recording may produce 0-byte files** — debug overlay added to diagnose
  - Possible causes: raw stream listener not firing, NAL parsing mismatch, muxer config failure
  - SD card recording works (drone-side command succeeds)
- **RTH fails indoors** — expected DJI behavior, needs GPS home point (8+ satellites)

## Build

```bash
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### Dependencies
- DJI MSDK V5 Aircraft SDK (`dji-sdk-v5-aircraft`, `dji-sdk-v5-aircraft-provided`)
- DJI MSDK V5 NetworkImp for RC-N3 USB connection
- Jetpack Compose (Material3)
- AndroidX Lifecycle/ViewModel

## Manifest Config
- `android:screenOrientation="sensorLandscape"` — forced landscape
- USB accessory auto-launch for RC-N3
- DJI API key in `<meta-data>`
- FileProvider for sharing recordings

## State Management
Single `DroneState` data class holds all UI state. `MainViewModel` collects from SDK flows and updates `_droneState: MutableStateFlow<DroneState>`. All UI components observe `droneState.collectAsState()`.

## Project Roadmap
1. **Phase 1 (current):** Flight controls, telemetry HUD, video feed, recording
2. **Phase 2:** Object detection — user selects bounding box → lightweight tracker (SAM 2 / OSTrack) follows object across frames
3. **Phase 3:** Autonomous path planning — orbit object at multiple altitudes for Gaussian Splatting capture, gimbal auto-tracks object center
