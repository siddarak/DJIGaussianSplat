# Graph Report - IR  (2026-04-21)

## Corpus Check
- 33 files · ~22,919 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 225 nodes · 192 edges · 20 communities detected
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]
- [[_COMMUNITY_Community 12|Community 12]]
- [[_COMMUNITY_Community 13|Community 13]]
- [[_COMMUNITY_Community 14|Community 14]]
- [[_COMMUNITY_Community 17|Community 17]]
- [[_COMMUNITY_Community 19|Community 19]]
- [[_COMMUNITY_Community 20|Community 20]]
- [[_COMMUNITY_Community 22|Community 22]]
- [[_COMMUNITY_Community 23|Community 23]]
- [[_COMMUNITY_Community 27|Community 27]]
- [[_COMMUNITY_Community 28|Community 28]]

## God Nodes (most connected - your core abstractions)
1. `MainViewModel` - 33 edges
2. `TelemetryManager` - 17 edges
3. `RecordingManager` - 16 edges
4. `OrbitExecutor` - 15 edges
5. `FlightController` - 9 edges
6. `VideoStreamManager` - 8 edges
7. `MissionPlanner` - 6 edges
8. `Mp4Muxer` - 5 edges
9. `GimbalController` - 5 edges
10. `DronesApplication` - 4 edges

## Surprising Connections (you probably didn't know these)
- None detected - all connections are within the same source files.

## Communities

### Community 0 - "Community 0"
Cohesion: 0.06
Nodes (1): MainViewModel

### Community 1 - "Community 1"
Cohesion: 0.11
Nodes (2): CodecType, RecordingManager

### Community 2 - "Community 2"
Cohesion: 0.12
Nodes (1): TelemetryManager

### Community 3 - "Community 3"
Cohesion: 0.12
Nodes (1): OrbitExecutor

### Community 4 - "Community 4"
Cohesion: 0.13
Nodes (14): Altitude, Battery, BatteryTemp, FlightModeUpdate, FlyingState, ForwardObstacle, GimbalAttitude, Heading (+6 more)

### Community 5 - "Community 5"
Cohesion: 0.14
Nodes (13): Aborted, Arming, Climbing, Done, Error, Flying, Idle, OrbitDirection (+5 more)

### Community 6 - "Community 6"
Cohesion: 0.2
Nodes (1): FlightController

### Community 7 - "Community 7"
Cohesion: 0.22
Nodes (1): VideoStreamManager

### Community 10 - "Community 10"
Cohesion: 0.29
Nodes (1): MissionPlanner

### Community 11 - "Community 11"
Cohesion: 0.33
Nodes (2): RecordingDebugLog, RecordingDebugStatus

### Community 12 - "Community 12"
Cohesion: 0.33
Nodes (1): Mp4Muxer

### Community 13 - "Community 13"
Cohesion: 0.33
Nodes (1): GimbalController

### Community 14 - "Community 14"
Cohesion: 0.4
Nodes (1): DronesApplication

### Community 17 - "Community 17"
Cohesion: 0.5
Nodes (1): MainActivity

### Community 19 - "Community 19"
Cohesion: 0.67
Nodes (1): ExampleInstrumentedTest

### Community 20 - "Community 20"
Cohesion: 0.67
Nodes (1): ExampleUnitTest

### Community 22 - "Community 22"
Cohesion: 0.67
Nodes (1): DjiSdkManager

### Community 23 - "Community 23"
Cohesion: 0.67
Nodes (2): DroneState, SdkConnectionState

### Community 27 - "Community 27"
Cohesion: 1.0
Nodes (1): OutputLayout

### Community 28 - "Community 28"
Cohesion: 1.0
Nodes (1): DetectionResult

## Knowledge Gaps
- **33 isolated node(s):** `OrbitMission`, `OrbitDirection`, `OrbitRing`, `OrbitPhase`, `OrbitState` (+28 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Community 0`** (34 nodes): `MainViewModel.kt`, `MainViewModel`, `.abortOrbit()`, `.cancelRth()`, `.clearSelection()`, `.confirmLanding()`, `.emergencyStop()`, `.getLastRecordingPath()`, `.getLastRecordingUri()`, `.handleTelemetryUpdate()`, `.land()`, `.lockGimbal()`, `.lockOrbitTarget()`, `.observeSdkState()`, `.onCleared()`, `.resetGimbal()`, `.returnToHome()`, `.scheduleErrorClear()`, `.selectDetection()`, `.selectRegion()`, `.setGimbalPitch()`, `.setupRecordingCallbacks()`, `.startDetectionStatusTicker()`, `.startGimbalLockEnforcement()`, `.startRecording()`, `.startRecordingTimer()`, `.startTelemetry()`, `.stopRecording()`, `.stopRecordingTimer()`, `.stopTelemetry()`, `.takeOff()`, `.toggleDebugOverlay()`, `.toggleSelectionMode()`, `.unlockGimbal()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 1`** (18 nodes): `RecordingManager.kt`, `CodecType`, `RecordingManager`, `.cleanup()`, `.createOutputFile()`, `.handleNalUnit()`, `.listenForControllerRecordButton()`, `.notifyStateChanged()`, `.processNalData()`, `.publishToMediaStore()`, `.startBothRecording()`, `.startDroneRecording()`, `.startOnDeviceRecording()`, `.stopBothRecording()`, `.stopDroneRecording()`, `.stopOnDeviceRecording()`, `.tryConfigureAvcMuxer()`, `.tryConfigureMuxer()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 2`** (17 nodes): `TelemetryManager`, `.listenAltitude()`, `.listenAttitude()`, `.listenBatteryPercent()`, `.listenBatteryTemp()`, `.listenFlightMode()`, `.listenFlyingState()`, `.listenForwardObstacle()`, `.listenGimbal()`, `.listenLandingConfirmation()`, `.listenLocation()`, `.listenSatelliteCount()`, `.listenSignal()`, `.listenVelocity()`, `.safeSubscribe()`, `.startListening()`, `.stopListening()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 3`** (16 nodes): `OrbitExecutor.kt`, `OrbitExecutor`, `.abort()`, `.cleanup()`, `.disableLandingProtection()`, `.disableVirtualStick()`, `.enableVirtualStick()`, `.executeSingleOrbit()`, `.executeTopShot()`, `.normalizeDeg()`, `.restoreProtection()`, `.runMission()`, `.sendVirtualStick()`, `.setupControlModes()`, `.start()`, `.transitionAltitude()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 6`** (10 nodes): `FlightController.kt`, `FlightController`, `.cancelReturnToHome()`, `.confirmLanding()`, `.emergencyStop()`, `.land()`, `.resultCallback()`, `.returnToHome()`, `.stopLanding()`, `.takeOff()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 7`** (9 nodes): `VideoStreamManager.kt`, `VideoStreamManager`, `.addDecodedFrameListener()`, `.addRawStreamListener()`, `.cleanup()`, `.removeDecodedFrameListener()`, `.removeRawStreamListener()`, `.startVideoFeed()`, `.stopVideoFeed()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 10`** (7 nodes): `MissionPlanner.kt`, `MissionPlanner`, `.computeCenter()`, `.executionOrder()`, `.gimbalPitch()`, `.planRings()`, `.startAngleDeg()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 11`** (6 nodes): `RecordingDebugLog.kt`, `RecordingDebugLog`, `.clear()`, `.log()`, `.updateStatus()`, `RecordingDebugStatus`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 12`** (6 nodes): `Mp4Muxer.kt`, `Mp4Muxer`, `.configureAvcCsd()`, `.configureHevcCsd()`, `.stop()`, `.writeNalUnit()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 13`** (6 nodes): `GimbalController.kt`, `GimbalController`, `.lockAtCurrentAngle()`, `.pointDown()`, `.resetToLevel()`, `.setPitch()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 14`** (5 nodes): `DronesApplication.kt`, `DronesApplication`, `.attachBaseContext()`, `.installCrashSafetyHandler()`, `.onCreate()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 17`** (4 nodes): `MainActivity.kt`, `MainActivity`, `.onCreate()`, `.requestMissingPermissions()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 19`** (3 nodes): `ExampleInstrumentedTest.kt`, `ExampleInstrumentedTest`, `.useAppContext()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 20`** (3 nodes): `ExampleUnitTest.kt`, `ExampleUnitTest`, `.addition_isCorrect()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 22`** (3 nodes): `DjiSdkManager.kt`, `DjiSdkManager`, `.init()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 23`** (3 nodes): `DroneState.kt`, `DroneState`, `SdkConnectionState`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 27`** (2 nodes): `LiveObjectDetector.kt`, `OutputLayout`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Community 28`** (2 nodes): `DetectionResult.kt`, `DetectionResult`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `TelemetryManager` connect `Community 2` to `Community 4`?**
  _High betweenness centrality (0.014) - this node is a cross-community bridge._
- **What connects `OrbitMission`, `OrbitDirection`, `OrbitRing` to the rest of the system?**
  _33 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.06 - nodes in this community are weakly interconnected._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.11 - nodes in this community are weakly interconnected._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.12 - nodes in this community are weakly interconnected._
- **Should `Community 3` be split into smaller, more focused modules?**
  _Cohesion score 0.12 - nodes in this community are weakly interconnected._
- **Should `Community 4` be split into smaller, more focused modules?**
  _Cohesion score 0.13 - nodes in this community are weakly interconnected._