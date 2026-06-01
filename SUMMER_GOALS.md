# Summer Goals — through end of June 2026

**Window:** 2026-05-23 → 2026-06-30
**See also:** [STATUS_UPDATE.md](STATUS_UPDATE.md) · [SESSION_LOG.md](SESSION_LOG.md) · [DEV_LOG.md](DEV_LOG.md)

---

## What "done for the summer" means

Three things, together:

1. **Working prototype** — APK that runs end-to-end capture on a real object (indoor via markers, outdoor via GPS)
2. **Working pipeline** — captured video feeds a downstream reconstruction tool and produces a usable splat

If something slips, the reason gets logged in [DEV_LOG.md](DEV_LOG.md).

---

## Must ship

- **Marker survey + indoor orbit, end-to-end** — drone surveys printed markers, builds a geofence, autonomously orbits the centroid, records video
- **GPS orbit validated outdoor** — at least one successful outdoor flight on a real object
- **Captured video → splat** — a recording produces a reasonable splat in COLMAP / Nerfstudio / 3DGS
- **Camera calibration** — one-time on-device calibration replaces the current hardcoded intrinsics
- **Paper draft in progress** — outline + at least one section populated

---

## Should ship

- Marker-frame orbit executor
- "Fly Fence" — drone visits each surveyed waypoint before orbit
- Pre-flight checklist screen
- Drone↔RC pairing screen in the app
- Vision Assist obstacle radar overlay
- Tap-to-orbit button wired in the UI

---

## Stretch

- Single-target detection mode
- Capture wizard (multi-step setup flow)
- Pull native 4K video from the drone SD card
- Save / load marker maps per environment
- Tune the visual-orbit control gains with real flight data

---

## Out of scope this summer

- Multiple objects at once
- Multiple drones
- Cloud upload
- iOS port

---

## Future scope (after June)

- **On-device splat preview** — render the resulting splat on the same phone right after capture. Relevant work: *Sort-free Gaussian Splatting via Weighted Sum Rendering* (Hou et al., ICLR 2025, [arxiv:2410.18931](https://arxiv.org/abs/2410.18931)) — mobile-friendly rendering that removes the sort step.
- Auto-survey (drone finds markers without manual fly-past)
- Marker-less indoor capture (visual-inertial SLAM)
- Tape-line follower between markers
- Public dataset release (video + poses + splats)

---

## How this doc changes

- Done items get a ✅ next to them
- Items moving between sections get a note in [DEV_LOG.md](DEV_LOG.md)
- End-of-June: final pass on what shipped vs. didn't
