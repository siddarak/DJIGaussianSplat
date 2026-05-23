# Status Update

**Project:** DJI Gaussian Splat Capture App
**Last updated:** 2026-05-23
**Canonical change log:** [SESSION_LOG.md](SESSION_LOG.md) — read that for the authoritative history; this doc is a snapshot.

---

## What this is

An Android companion app for the DJI Mini 4 Pro drone that automates **video capture for 3D Gaussian Splat reconstruction**. The drone flies hemispherical orbits around a user-selected object while the gimbal tracks the object's center, producing a full-coverage video dataset for downstream NeRF / COLMAP / 3DGS pipelines. The current research focus is **indoor capture without GPS**, using printed ArUco fiducial markers to establish a world coordinate frame so the drone can fly an autonomous orbit indoors.

---

## Why it exists

3D Gaussian Splatting needs many camera views around the target. Hand-flying a drone for 5–10 minutes per object is tedious and produces inconsistent coverage. This app automates that capture. The marker-survey branch also aims to associate captured frames with absolute camera poses, which could accelerate the downstream reconstruction pipeline.

---

## Where the project is right now

**Phase 3** of three. Phases 1 and 2 are code-complete; Phase 3 (marker localization) is mid-build.

- 11 tagged milestones shipped (`v1.0-recording-baseline` → `v2.2-survey`)
- Tested in indoor hand-held mode; **not yet flight-tested**
- Code lives at this repo; APKs auto-built per tag via `./gradlew assembleDebug`

Legend used below:
- ✅ working and tested on device
- ⏳ code-complete but not yet validated in a real flight or with real hardware
- 📝 planned, no code yet

---

## What works ✅

| Area | State |
|---|---|
| DJI SDK V5 registration + RC-N3 connection | ✅ |
| Live video feed (H.265 NV21 + raw stream) | ✅ |
| Telemetry HUD (altitude, speed, heading, battery, signal) | ✅ |
| Manual flight controls (takeoff, land, RTH, emergency stop) | ✅ |
| Gimbal control (pitch, lock, recenter) | ✅ |
| On-device + drone-SD recording (H.265 with proper MediaMuxer CSD) | ✅ |
| MediaStore publishing — videos appear in Samsung Gallery / Files | ✅ |
| Per-flight log files in `Android/data/.../files/flights/` + share via Drive | ✅ |
| Live object detection (EfficientDet-Lite0, COCO classes) | ✅ |
| OpenCV 4.10 native lib loads on device | ✅ |
| Live ArUco marker detection on NV21 Y-plane (DICT_4X4_50, ~5 fps, ~10 ms/frame) | ✅ |
| Survey UI: top-down preview, chronological waypoint list, ADD-WP CTA, JSON persist | ✅ |
| Cockpit UX: left rail + single bottom mode CTA + survey panel | ✅ |

---

## What's untested ⏳

| Area | Why untested | What we need |
|---|---|---|
| GPS satellite count populating outdoors | requires outdoor flight | clear-sky flight |
| GPS orbit (full hemisphere mission) | requires GPS lock + outdoor space | outdoor flight test |
| Visual orbit (no-GPS body-frame servo) | control gains untuned | indoor flight test, tune K_YAW / K_FWD |
| Auto-yaw on detection tap | requires flying drone | indoor flight |
| Tap-to-orbit chain | UI button not wired yet, logic exists | small UI patch + flight |
| Marker survey co-observation math | needs real printed markers in measured layout | ground test with tape-measured marker positions |
| ArUco distance accuracy | depends on camera intrinsics (currently approximated) | one-time checkerboard calibration |
| MarkerOrbitExecutor (orbit in marker frame) | not built yet | follows survey validation |

---

## What's planned 📝

| Item | Notes |
|---|---|
| Drone↔RC pairing screen in-app | replaces DJI Fly first-launch; MSDK exposes `KeyRequestPairing` |
| Pre-Flight checklist screen | gates Cockpit on SDK / drone / GPS / battery / SD readiness |
| Capture Wizard (multi-step modal for marker setup) | calibration → place → survey → confirm → centroid → altitude → review |
| Vision Assist PiP radar | DJI `PerceptionManager` obstacle distances rendered as 8-sector compass overlay |
| Single-target detection mode | pick highest-confidence detection nearest frame center, suppress others |
| Camera calibration UI | OpenCV checkerboard, replaces hardcoded intrinsics |
| FLY FENCE button | drone traverses surveyed waypoint polygon with gimbal facing centroid |
| Drone SD-card video pull | grab native 4K MP4 to phone for COLMAP/3DGS |

---

## Hardware to run / test

- **Drone:** DJI Mini 4 Pro (with battery, propellers, microSD)
- **Controller:** DJI RC-N3 (USB cable to phone) — RC-2 / RC-3 will also work with same APK
- **Phone:** Android 10+ with USB-C
- **Markers (for indoor survey):** 4–6 printed ArUco markers (`DICT_4X4_50`, IDs 0–49), **150 mm** side length, on rigid backing
  - Generator: `https://chev.me/arucogen/` — pick "Original ArUco" or "4x4_50", size 150 mm, print at 100% scale
- **Optional:** tape measure (for tape-measured marker layout when ground-truthing survey math)

---

## How to get the APK

From the repo root:

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/drones-debug-<git-describe>.apk
```

APK filenames are derived from the latest git tag (`drones-debug-v2.2-survey.apk` right now). Old APKs are auto-deleted on each build, so only one binary exists at any time.

Sideload to phone via USB cable + `adb install <apk>` or transfer + tap to install.

---

## Where to read next

- [SESSION_LOG.md](SESSION_LOG.md) — every code change, tag, and burned-cycle root cause
- [CLAUDE.md](CLAUDE.md) — full technical reference (MSDK API, recording pipeline, coordinate systems)
- `SUMMER_GOALS.md` — what we plan to finish by end of June (draft pending)
- `DEV_LOG.md` — decisions, blockers, backlog (draft pending)
