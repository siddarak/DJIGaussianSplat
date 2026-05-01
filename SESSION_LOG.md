# Session Log

Running ledger of every change in this project. Wins, fails, supersessions, untested items.
Format: `[date] vX.Y-tag — what / why / status`. Status: ✅ confirmed / ⏳ untested / ❌ failed / 🔄 superseded.

---

## Phase 0 — Bootstrap (Mar 2026)

### 2026-03-13
- **70ba882** initial commit. DJI MSDK V5 video capture for Gaussian splatting target. Bare scaffolding.
- **1e1a220** build fixes: Java version, AndroidX, DJI `Helper.install`, SDK deps wired. ✅

### 2026-03-23
- **387f1e3** full app rebuild: HUD, telemetry, recording, object selection. Big bang rewrite.
- **c79904a** H.265 NAL parsing + `GimbalResetType` enum corrected for Mini 4 Pro.
- **916206f** H.265 MediaMuxer CSD format fix for correct MP4.
- **a5350e3** HEVC CSD: concat VPS+SPS+PPS into single `csd-0`. Separate csd-0/csd-1 (H.264 pattern) produces broken MP4 most decoders reject. ✅
- **717a7d2** repo cleanup: removed heap dump, DJI samples, `.claire`, `.DS_Store`. `.gitignore` updated.

### 2026-03-25
- **78f71a4** recording pipeline + gimbal lock + force landscape + debug overlay.
- **ee06947** ▶ tag **v1.0-recording-baseline** — VideoStreamManager debug instrumentation + README. ✅ last known good recording-only build.

## Phase 1 — Detection + Orbit foundation (Apr 2026)

### 2026-04-15
- **cd569e6** hemispherical orbit system: `OrbitMission`, `MissionPlanner`, `OrbitExecutor` skeleton.
- **e6912fe** live object detection v1: EfficientDet-Lite0 + overlay + orbit-lock flow.
- **cd4b0aa** 5-bug fix bundle: wire detection overlay, fix emergency stop no-op, add orbit UI, frame bounds check, configure virtual stick control modes. Critical: control modes must be set on every VS param send. ✅

### 2026-04-16
- **3cd11b7** detection re-add (recovery commit).

### 2026-04-22
- **26af0c9** ▶ tag **v1.1-detection** — detector rewrite, Java home pinned to Android Studio JBR, H.264 recording path, `CLAUDE.md` written. ✅

### 2026-04-28 — Detection model debugging marathon
- **e27226e** 3 critical orbit bugs: control modes per-tick, gimbal angle math (`centerAlt = objectHeight/2`), ring altitude reference (`lockAlt + objectHeight`, not `centerAlt + objectHeight`). ✅ code-correct, ⏳ flight-untested
- **bb549f2** crash fix: drop frames when inference busy. `AtomicBoolean inferencing` prevents queue buildup → OOM. ✅
- **b9bf7b8** TFLite "stateful partitioned call" error fix: use `ByteBuffer` outputs sized to exact tensor element count.
- **9c08070** scores/classes swap threshold tweak 1.5→1.05. 🔄 superseded once tensor layout dynamic.
- **6d11e61** hardcoded tensor order. 🔄 superseded by dynamic detection.
- **c07d2ba** dynamic output layout, handle <4 outputs. ❌ this allowed proceeding with WRONG model file (raw, no NMS) — silently filled zeros. Root cause not yet identified at this point.
- **5003d72** ▶ refactor: split monolithic detector into `ModelInspector`, `TFLiteRunner`, `FrameProcessor`, `LiveObjectDetector`. Pure ML logic isolated from DJI wiring.
- **891d79e** ▶ tag **v1.1.1-detector-config** — centralize all model-specific config in `DetectorConfig.kt`. Swapping model = one file change. ✅
- **e5195d7** tensor diagnostic in debug overlay (`s0`, `s1`, `c0`, `c1`, `b0`).
- **4781ac9** build script: auto-version APK from `git describe`, delete stale APKs each build. ✅

### 2026-04-29 — Big day
- **1909f73** ▶ tag **v1.2-logbutton** — bitmap luma diagnostic added. **FILES button** wired into TopHudBar (clipboard copy + share intent via FileProvider; Android 11+ blocks `/Android/data/` direct browse). Per-flight folder structure: `flights/flight-yyyy-MM-dd-HH-mm-ss/log.txt`. ✅ user shared log via Drive successfully
- **6a28bc6** ▶ tag **v1.3-detection-model-fix** — 🎯 ROOT CAUSE FIX. Diagnostic showed `n=2 N=19206`: model was raw EfficientDet (no NMS), had only 2 outputs. Replaced asset with post-processed variant (4 outputs, ~25 detections, NMS embedded, 4.4MB uint8) from TFLite Task Library hosted URL. Detection started producing real results. ✅ confirmed working
- **34522da** ▶ tag **v1.3.1-detect-filter** — `DetectorConfig.ALLOWED_LABELS` whitelist (chair/couch/bed/tv/laptop/fridge/etc, drops frisbee/food/utensils). Threshold raised 0.05 → 0.30. ✅
- **99fbac1** ▶ tag **v1.4-autoyaw-visual** — `AutoYaw` rotates drone toward tapped detection (Mini 4 Pro HFOV ≈ 73°, 3° tolerance, 3s timeout, virtual stick angle yaw). `VisualOrbitExecutor` body-frame visual servo for sat<6/indoor (yawRate from box X-error, forwardVel from box-size error, lateral 0.5 m/s constant). `lockOrbitTarget()` auto-routes GPS vs visual on sat count. ⏳ untested — gains untuned (K_YAW=60, K_FWD=1.5)
- **03d9b63** ▶ tag **v1.5-public-files** — `MediaPublisher` copies private logs to `Documents/Drones/` via MediaStore so Samsung Files / Drive picker can see them. Same path for video. `Mp4Muxer.stop()` deletes 0-byte files (no frames written = corrupt MP4). ⏳ untested
- **c83bb51** ▶ tag **v1.6-telemetry-seed** — first attempt at GPS=0 fix: `getValue()` seed before `listen()`. 🔄 superseded — root cause was using wrong listen overload, see v1.7

## Phase 2 — Telemetry + Vision Assist (May 2026)

### 2026-05-01
- **9da33ac** ▶ tag **v1.7-sat-fix** — 🎯 ROOT CAUSE FIX. Decompiled `dji-sdk-v5-aircraft-provided-5.17.0.jar`, found `KeyManager.listen(key, holder, getValueOnSubscribe: Boolean, listener)` 4-arg overload. 3-arg (what we used) fires only on changes — drone already had GPS lock when subscribed, value never *changed*, so callback never fired. Switched all 14 listeners to 4-arg with `true`. Bonus: added `KeyGPSIsValid` + `KeyGPSSignalLevel` listeners, `isKeySupported` logged on subscribe. ⏳ untested (need outdoor test)
- **17c2abb** docs: SESSION_LOG.md created. (this file)

---

## Open / pending

| Item | Status |
|---|---|
| Sat fix v1.7 | ⏳ verify outdoor with real GPS lock |
| GPS orbit | ⏳ never flight tested (code-correct since v1.1.1) |
| Visual orbit | ⏳ never tested, gains untuned |
| Tap-to-orbit chain | ⏳ wired but no UI button |
| Vision Assist + indoor flight | 📝 next ticket — `VisionAssistManager.isVisionPositioningSensorEnabled()`, downward obstacle distance, PiP overlay 150×150dp toggleable swap with main camera, synthetic radar render (no raw down-cam frames per MSDK privacy) |
| Single-target detection | 📝 last — pick highest-conf nearest center, suppress rest |
| Detection box blink animation | 📝 imports added in v1.6, draw code unfinished |

## Burned cycles (avoid repeating)

| Date | Spiral | Root cause | Fix |
|---|---|---|---|
| 2026-04-28 | 4-hr detection model returning all-zero outputs | Wrong TFLite file: raw 2-output (19206 anchors, no NMS) instead of post-processed 4-output | v1.3 model swap. Memory note saved at `~/.claude/projects/.../memory/project_detection_model.md` |
| 2026-05-01 | GPS read 0 outdoors | 3-arg `listen()` only fires on changes; drone GPS already locked at subscribe time | v1.7 4-arg `listen(..., getValueOnSubscribe=true, ...)` |
| Various | "Stateful partitioned call" copy error | Output buffer shape mismatch | `ByteBuffer.allocateDirect(n*4)` sized to exact tensor element count |
| Various | TFLite OOM crash | Frame queue buildup faster than inference | `AtomicBoolean inferencing` drop-frame-if-busy |
| Multiple | HEVC MP4 unplayable | Wrong CSD layout (csd-0/csd-1 split for HEVC) | Single `csd-0 = startCode+VPS + startCode+SPS + startCode+PPS` |
| Once | Drone won't accept virtual stick | Control modes set once at start, drone defaults reapplied per param | Set all 4 mode fields on every `sendVirtualStickAdvancedParam` call |
| Once | Gimbal stuck flat during orbit | `centerAlt = droneAlt` → `atan2(0, R) = 0°` | `centerAlt = objectHeight/2.0` |

## Versioning rules (active)

- APK auto-named `drones-debug-<gitDescribe>.apk` via `app/build.gradle.kts` (post-tag rebuild needed for clean name)
- Old APKs auto-deleted before each `package` task
- Tag scheme: `vX.Y-feature` for milestones, `vX.Y.Z-detail` for checkpoints
- Every commit pushed to `https://github.com/siddarak/DJIGaussianSplat.git`

## Files of record

- `CLAUDE.md` — durable project context (architecture, MSDK API patterns, coordinate systems, recording pipeline)
- `SESSION_LOG.md` — this file
- `~/.claude/projects/-Users-siddheshdarak-Documents-VS-CODE-IR/memory/` — cross-conversation memory (model file requirements, user profile, no-attribution rule)
