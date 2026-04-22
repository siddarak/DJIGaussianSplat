# CLAUDE.md — DJI Gaussian Splat Capture App

This file is read automatically by Claude at the start of every session. Keep it current.

---

## What This Is

Android app for DJI Mini 4 Pro (RC-N3 controller). Goal: autonomous hemispherical video capture around a user-selected object for 3D Gaussian Splatting reconstruction. The drone flies multiple altitude rings around the object while the gimbal tracks the center, producing a full-sphere video dataset.

**Current state:** All three pipelines (recording, detection, orbit) are wired end-to-end and compile. The app has been tested on-device. Orbit has not been flight-tested yet.

---

## Hardware

- Drone: DJI Mini 4 Pro
- Controller: RC-N3 (USB accessory, not Wi-Fi)
- SDK: DJI MSDK V5, version 5.17.0

---

## Architecture

```
data/DroneState.kt           — single state class for all UI; updated by MainViewModel
sdk/
  DjiSdkManager.kt           — SDK registration, productConnected StateFlow
  TelemetryManager.kt        — all KeyManager listeners → TelemetryUpdate sealed class
  FlightController.kt        — takeoff/land/RTH/emergencyStop via KeyManager
  GimbalController.kt        — setPitch/resetToLevel; currentPitchEstimate volatile
  VideoStreamManager.kt      — surface pipeline + raw/decoded stream listeners
recording/
  RecordingManager.kt        — on-device H.264+H.265 muxing + drone SD recording
  Mp4Muxer.kt                — MediaMuxer wrapper; configureHevcCsd() / configureAvcCsd()
  RecordingDebugLog.kt       — in-app debug log (StateFlow, no ADB needed)
detection/
  LiveObjectDetector.kt      — EfficientDet-Lite0 TFLite on NV21 decoded frames, 8fps
  DetectionResult.kt         — label, confidence, boxNorm (0–1), trackId
orbit/
  OrbitMission.kt            — mission spec + OrbitRing + OrbitState sealed class
  MissionPlanner.kt          — planRings() + executionOrder() + computeCenter()
  OrbitExecutor.kt           — virtual stick coroutine executor
ui/
  MainViewModel.kt           — orchestrates everything, single DroneState flow
  screens/FlightScreen.kt    — root screen layout
  components/                — VideoFeedView, FlightControlsPanel, etc.
```

---

## DJI SDK API — Verified Facts

These were confirmed by decompiling the actual JAR from Gradle cache. Do not guess or use deprecated patterns.

### Init (mandatory)
```kotlin
// In Application.attachBaseContext():
com.cySdkyc.clx.Helper.install(base)  // DJI native lib loader — MUST be first
```

### Key API pattern
```kotlin
KeyManager.getInstance().performAction(KeyTools.createKey(SomeKey.KeyName), param, callback)
KeyManager.getInstance().setValue(KeyTools.createKey(SomeKey.KeyName), value, callback)
KeyManager.getInstance().listen(key, holder) { old, new -> ... }
KeyManager.getInstance().cancelListen(holder)   // cancels all keys for that holder
```

### Virtual Stick (OrbitExecutor)
```kotlin
VirtualStickManager.getInstance().enableVirtualStick(callback)
VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(VirtualStickFlightControlParam())
```
- `VirtualStickFlightControlParam` fields are `Double` (not Float): `pitch`, `roll`, `yaw`, `verticalThrottle`
- Must set `rollPitchControlMode = VELOCITY`, `rollPitchCoordinateSystem = GROUND` (Earth/NED frame), `yawControlMode = ANGLE`
- In GROUND frame: `pitch` = North velocity, `roll` = East velocity, `yaw` = absolute heading

### Camera Stream
```kotlin
// Decoded NV21 frames (for object detection):
ICameraStreamManager.addFrameListener(ComponentIndexType.LEFT_OR_MAIN, NV21, listener)
// Listener signature (6 params):
CameraFrameListener { data: ByteArray, offset: Int, length: Int, width: Int, height: Int, format: FrameFormat -> }

// Raw encoded stream (for recording):
ICameraStreamManager.addReceiveStreamListener(listener)
// Listener signature (4 params):
ReceiveStreamListener { data: ByteArray, offset: Int, length: Int, info: StreamInfo -> }

// Surface for decoded video display:
ICameraStreamManager.putCameraStreamSurface(surface, w, h, ScaleType.CENTER_INSIDE)
```
**Critical ordering fix:** The decoded frame listener must be re-registered AFTER `putCameraStreamSurface()` — otherwise DJI's decoder isn't initialized and NV21 frames never arrive. VideoStreamManager handles this.

### Obstacle Sensor
```kotlin
KeyTools.createKey(FlightAssistantKey.KeyOmniHorizontalRadarDistance)  // returns Double, meters
// Mini 4 Pro valid range: 0.38–20m. Returns 0 when out of range.
```

### Landing Protection Override (for low orbit ring)
```kotlin
KeyTools.createKey(FlightAssistantKey.KeyLandingProtectionEnabled)    // Boolean
KeyTools.createKey(FlightAssistantKey.KeyDownwardsAvoidanceEnable)    // Boolean
// Set both to false before LOW ring, restore to true after.
```

### Gimbal
```kotlin
KeyTools.createKey(GimbalKey.KeyRotateByAngle)   // param: GimbalAngleRotation
KeyTools.createKey(GimbalKey.KeyGimbalReset)     // param: GimbalResetType.PITCH_YAW
// Mini 4 Pro pitch range: ~-90° (straight down) to +35° (slightly up)
```

---

## Recording Pipeline

The drone sends either **H.265 (HEVC)** or **H.264 (AVC)** — codec is auto-detected from NAL types.

### H.265 NAL types
- 32 = VPS, 33 = SPS, 34 = PPS → collect all three, then call `configureHevcCsd(vps, sps, pps)`
- 19, 20 = IDR keyframe → `writeNalUnit(..., isKeyFrame=true)`
- other = P/B frame → `writeNalUnit(..., isKeyFrame=false)`

### H.264 NAL types
- 7 = SPS, 8 = PPS → collect both, then call `configureAvcCsd(sps, pps)`
- 5 = IDR keyframe → `writeNalUnit(..., isKeyFrame=true)`
- 1 = P-frame → `writeNalUnit(..., isKeyFrame=false)`

### MediaMuxer CSD format
- **HEVC:** single `csd-0` = `startCode+VPS + startCode+SPS + startCode+PPS` (concatenated)
- **AVC:** separate `csd-0` = `startCode+SPS`, `csd-1` = `startCode+PPS`
- NAL data passed to `configureHevcCsd/configureAvcCsd` must NOT include start codes — they are prepended internally

---

## Orbit System

### Lock flow
1. User flies drone close to object, obstacle sensor reads distance (0.38–20m)
2. `MainViewModel.lockOrbitTarget()` → `MissionPlanner.computeCenter()` computes object GPS position
3. `MissionPlanner.planRings()` generates rings: LOW → EQUATORIAL → MID/HIGH → TOP_SHOT
4. `MissionPlanner.executionOrder()` reorders: EQ → LOW → EQ → ascending → TOP
5. `OrbitExecutor` executes via virtual stick at 10Hz

### Orbit math (Earth/NED frame)
```kotlin
vNorth = -speed * sin(angle) * sign
vEast  =  speed * cos(angle) * sign
yaw    = normalizeDeg(toDegrees(angle) + 180.0)   // drone faces center
gimbalPitch = -toDegrees(atan2(droneAlt - centerAlt, radius)).coerceIn(-85.0, 35.0)
```

### Center computation
```kotlin
centerLat = droneLat + (distanceM * cos(headingRad)) / 111111.0
centerLon = droneLon + (distanceM * sin(headingRad)) / (111111.0 * cos(toRadians(droneLat)))
```

---

## Object Detection

- Model: EfficientDet-Lite0 TFLite (`assets/efficientdet_lite0.tflite`), 80 COCO classes
- Threshold: 0.30 confidence
- Rate: throttled to 8fps (`FRAME_INTERVAL_MS = 125ms`)
- GPU delegate with CPU fallback
- Frame format: NV21 → JPEG → Bitmap → TensorImage (via `YuvImage.compressToJpeg`)
- Results: normalized 0–1 bounding boxes, label, confidence, trackId (= index per frame, not persistent)

---

## State Management

`DroneState` is the single source of truth for all UI state. `MainViewModel` owns `_droneState: MutableStateFlow<DroneState>` and updates it via `update { it.copy(...) }`. All Compose components read `droneState.collectAsState()`.

Key state fields:
- `productConnected` — drone physically connected
- `isFlying`, `isTakingOff`, `isLanding`, `isRth` — flight status
- `isLandingConfirmationRequired` — wired to `KeyIsLandingConfirmationNeeded`
- `forwardObstacleDistM` — live obstacle sensor (0 = no reading)
- `orbitState` — `OrbitState` sealed class (Idle/Arming/Climbing/Flying/TopShot/Done/Aborted/Error)
- `detections` — live list of `DetectionResult`
- `selectedDetectionId` — trackId of tapped detection box

---

## Known Limitations / Watch Out For

1. **trackId is per-frame index, not a persistent tracker** — tapping a box selects it for that frame only; it can jump to a different object next frame if detection order changes
2. **Gimbal lock enforcement** fires every 500ms forever with no verification — RC scroll wheel can still override temporarily
3. **Landing protection restore** happens in the catch block of `runMission()` — if `abort()` is called mid-LOW-ring, both `runMission` catch AND `abort()` call `restoreProtection()` (harmless double-call)
4. **VPS/SPS/PPS race** — `@Volatile` fields but non-atomic read-modify-write in `tryConfigureMuxer`; in practice fine because DJI streams parameter sets before any IDR
5. **`KeyIsLandingConfirmationNeeded`** — key name verified as correct for MSDK V5; wrapped in `safeSubscribe` so if it doesn't exist it fails silently
6. **Orbit not flight-tested** — all code compiles and logic is correct but no real flight test yet

---

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Git tag `v1.0-recording-baseline` = last known good recording-only build (commit `ee06947`).
