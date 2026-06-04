# Dev Log

Live notes on decisions, blockers, and what's queued. New entries on top. Append-only — old entries don't get rewritten.

**See also:** [STATUS_UPDATE.md](STATUS_UPDATE.md) · [SUMMER_GOALS.md](SUMMER_GOALS.md) · [SESSION_LOG.md](SESSION_LOG.md) (every code change)

---

## How to use this log

- One entry per change of plan, blocker, or decision. Date-stamp it.
- Code-level history lives in [SESSION_LOG.md](SESSION_LOG.md). This file is for the *why*, not the *what*.
- Don't edit old entries. If something changes later, add a new entry pointing back.

---

## Decisions

### 2026-06-04 — Marker-anchored path + object config pipeline
New indoor capture method (replaces chain-survey idea):
- Climb to top, see all 8 markers in one frame, compute geometric center + marker map (most accurate fix).
- Plan dome = 3 concentric rings (mix of shrinking radius + rising height + steeper gimbal) + top shot. Rings stay outside the **inner** perimeter (hard keep-out, object lives inside it); may overfly the **outer** perimeter (soft, localization refs only).
- Fly each ring **open-loop** (dead-reckon X/Y), then **return to top and re-anchor** off markers to wipe drift. Height is **closed-loop** the whole time via downward VPS/ToF — vertical drift ~nil; staying outside inner perimeter keeps the downward sensor seeing floor not object.
- Marker IDs assign role: inner = 0–3, outer = 4–7.
- **Always confirm before flying**, even in auto. Show all rings on screen; user can scale circle size at the top (re-validated against keep-out) before ring 1. Per-ring confirm.
- Object dimensions come from CAD, processed **off-device** by `tools/cad_dims/extract_dims.py` → `objects/<id>.json` (meters, Z-up, units mandatory or "UNKNOWN" → app confirms). App reads via `ObjectConfigRepository` from externalFilesDir/objects/. Table height measured on site (separate from CAD). Model always upright; facing irrelevant (full orbit).
- **v2.4-objectconfig** shipped this pipeline foundation. Next: `MarkerPathPlanner` + preview/confirm UI + open-loop `MarkerOrbitExecutor`.

### 2026-05-24 — Reconstruction playgrounds (private forks)
Don't vendor the 3DGS research code into this repo (large, CUDA, separate licenses). Instead, two **private** mirror forks under `siddarak`:
- [siddarak/gaussian-splatting](https://github.com/siddarak/gaussian-splatting) ← graphdeco-inria/gaussian-splatting (standard 3DGS training)
- [siddarak/sort-free-gs](https://github.com/siddarak/sort-free-gs) ← LiYukeee/sort-free-gs (mobile sort-free rendering)
Private (not GitHub forks, since forks of public repos force-public) via mirror-clone → push to new private repo. `RECONSTRUCTION.md` documents the capture→splat pipeline + how 3DGS works. Research angle: marker-survey poses substitute for COLMAP SfM.

### 2026-05-23 — Doc structure
Split into four files: `STATUS_UPDATE`, `SUMMER_GOALS`, `DEV_LOG`, `SESSION_LOG`. Repo-only, no Notion mirror. Project-focused (no contributor table, no how-to-contribute section).

### 2026-05-06 — Marker size = 150 mm
Default printed ArUco marker side length set to 150 mm. Reliable detection range to ~5 m at 1080p; smaller sizes (50 mm) only work ~1 m, larger sizes are awkward to print and place.

### 2026-05-06 — Survey via co-observation chain
First detected marker is the world origin. Each new marker must be co-visible with at least one already-locked marker; world position derived from the relative transform. Simplification: markers assumed flat on the ground (shared Z plane).

### 2026-05-01 — Telemetry uses 4-arg `KeyManager.listen`
3-arg `listen()` only fires on changes. If the drone already has a value at subscribe time, the callback never fires. The 4-arg overload with `getValueOnSubscribe = true` pushes the current value immediately. All 14 telemetry listeners switched.

### 2026-04-29 — Detection model file: must have NMS embedded
The TFLite EfficientDet-Lite0 from TFHub / MediaPipe ships as a raw model (2 outputs, ~19k anchors, no NMS). The pipeline expects the post-processed variant (4 outputs, ~25 detections). Burned a day before realizing this. The known-good URL is in the model file's header comment.

### 2026-04-28 — Per-tick virtual stick control modes
`VirtualStickFlightControlParam` doesn't persist control modes. Each frame sent has to set all four mode fields (roll/pitch, yaw, vertical, coordinate system) or the drone falls back to defaults.

### 2026-03-23 — HEVC MediaMuxer CSD format
Single `csd-0` containing concatenated VPS + SPS + PPS with start codes. The H.264 split (`csd-0` SPS, `csd-1` PPS) produces a broken MP4 for HEVC.

---

## Blockers

- **GPS sat-fix verification** — needs outdoor flight to confirm the 4-arg listen change actually populates `Sat count > 0`. Indoor returns 0 by hardware reality.
- **Marker survey math** — co-observation transform is currently approximated (markers assumed perfectly flat, frames aligned with world axes). Real measurements with a tape may show drift. Not validated yet.
- **Downstream splat pipeline** — captured video has never been fed through COLMAP / Nerfstudio / 3DGS. Pipeline assumed working but not tested.
- **Camera intrinsics** — hardcoded from spec sheet (focal ≈ 1300 px, no distortion). Pose distances will be off until a real calibration runs.

---

## Backlog

Higher items are closer to ready to work on.

1. Camera calibration UI (one-time checkerboard hold)
2. `MarkerOrbitExecutor` — orbit math in marker frame
3. `FLY FENCE` button — visit each waypoint with gimbal facing centroid
4. Drone↔RC pairing screen
5. Pre-flight checklist screen
6. Vision Assist obstacle radar PiP overlay
7. Tap-to-orbit UI button (logic already exists)
8. Single-target detection mode toggle
9. Drone SD-card 4K video pull
10. Per-environment marker map presets

---

## Archive

(none yet — items move here once they're no longer relevant)
