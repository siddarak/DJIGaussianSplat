# DJI Gaussian Splat Capture App

Android companion app for the DJI Mini 4 Pro that automates drone video capture for 3D Gaussian Splat reconstruction.

---

## What it does

The drone flies hemispherical orbits around a chosen object while the gimbal tracks the centre, producing the multi-view video needed for downstream NeRF / COLMAP / 3DGS reconstruction. Two modes:

- **Outdoor (GPS):** lock the object via the forward obstacle sensor, run a multi-altitude orbit.
- **Indoor (ArUco markers):** survey printed markers to establish a world frame, build a geofence from them, then orbit inside it.

---

## Project docs

| File | What's in it |
|---|---|
| [STATUS_UPDATE.md](STATUS_UPDATE.md) | Current snapshot of what works, what doesn't, what's planned |
| [SUMMER_GOALS.md](SUMMER_GOALS.md) | What we plan to ship by end of June 2026 |
| [DEV_LOG.md](DEV_LOG.md) | Decisions, blockers, backlog |
| [SESSION_LOG.md](SESSION_LOG.md) | Every code change ever (tied to git tags) |
| [RECONSTRUCTION.md](RECONSTRUCTION.md) | How a capture becomes a splat + the two playground repos |
| [CLAUDE.md](CLAUDE.md) | Technical reference: MSDK API, recording pipeline, coordinate systems |

---

## Architecture

```
data/          single state class for the whole UI
sdk/           DJI MSDK V5 wrappers — registration, telemetry, flight, gimbal, video
recording/     H.265 / H.264 muxing to MP4 + MediaStore publishing
detection/     EfficientDet-Lite0 TFLite for live object detection
localization/  ArUco marker detection, camera intrinsics, survey controller, marker map
orbit/         mission planner, GPS orbit executor, visual orbit executor, auto-yaw
ui/            Compose UI — single cockpit screen with left rail and bottom mode CTA
util/          per-flight log files, media publishing, file share
```

See [CLAUDE.md](CLAUDE.md) for details.

---

## Hardware

- **Drone:** DJI Mini 4 Pro
- **Controller:** DJI RC-N3, RC-2, or RC-3 (same APK works on all)
- **Phone:** Android 10+ with USB-C
- **Markers (indoor only):** printed ArUco `DICT_4X4_50`, 150 mm side length, on rigid backing. Generator: https://chev.me/arucogen/

---

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/drones-debug-<git-tag>.apk
```

APKs are auto-named from the latest git tag and old APKs are auto-removed each build.

---

## Status at a glance

Most pipelines are code-complete. Marker survey was the latest milestone (`v2.2-survey`). The biggest open item is end-to-end validation in real flight — see [STATUS_UPDATE.md](STATUS_UPDATE.md) for the breakdown.
