# Session Log

Running ledger of everything done. Every change, every tag, every fail.
Format: `[date] vX.Y — what / why / status`. Status: ✅ confirmed / ⏳ untested / ❌ failed / 🔄 superseded.

---

## 2026-05-01

- **v1.7-sat-fix** — switched all 14 telemetry listeners to 4-arg `listen(key, holder, true, listener)`. Decompiled MSDK JAR confirmed overload exists; 3-arg form fires only on changes. Added `KeyGPSIsValid` + `KeyGPSSignalLevel` listeners, all logged to flight log. ⏳ untested (need outdoor)
- **v1.6-telemetry-seed** — attempted GPS fix via `getValue()` seed before listen. 🔄 superseded by v1.7 (4-arg listen handles seeding cleanly)

## 2026-04-29

- **v1.5-public-files** — `MediaPublisher` copies log → `Documents/Drones/`, uses MediaStore so Samsung Files/Gallery can see them. `Mp4Muxer.stop()` now deletes 0-byte files. ⏳ untested
- **v1.4-autoyaw-visual** — `AutoYaw` rotates drone on detection tap (HFOV=73°). `VisualOrbitExecutor` body-frame visual servo for sat<6 / indoor. `lockOrbitTarget()` auto-routes GPS vs visual on sat count. ⏳ untested
- **v1.3.1-detect-filter** — `DetectorConfig.ALLOWED_LABELS` whitelist (chair/couch/bed/etc, drops frisbee/food/etc). Threshold raised 0.05→0.30. ✅ user confirmed working
- **v1.3-detection-model-fix** — replaced raw EfficientDet (2 outputs, 19206 anchors, no NMS) with post-processed variant (4 outputs, ~25 detections, NMS embedded, 4.4MB uint8). Source: TFLite Task Library. ✅ detection works after this. Memory note saved.

## 2026-04-28

- **v1.2-logbutton** — FILES button on TopHudBar. Per-flight folder under `flights/flight-<ts>/log.txt`. `FileBrowser` uses FileProvider + `ACTION_SEND` because Android 11+ blocks `/Android/data/`. Diagnostic added to TFLiteRunner: bitmap luma + tensor preview values. ✅ shared via Drive worked
- **v1.1.1-detector-config** — centralized model config in `DetectorConfig.kt`. Swapping model = one file change. ✅
- **build script** — auto-version APK from git tag, delete stale APKs each build. ✅

---

## Open / pending

- ⏳ Sat fix (v1.7) — verify outdoor with real GPS lock
- ⏳ Orbit (GPS path) — never flight tested
- ⏳ Visual orbit — never flight tested, gains untuned (K_YAW=60, K_FWD=1.5, lateral=0.5 m/s)
- ⏳ Tap-to-orbit chain — wired but no UI button yet
- 📝 **Vision Assist + indoor flight** — next ticket. Use `VisionAssistManager.isVisionPositioningSensorEnabled()` + downward obstacle distance. PiP overlay 150x150dp with toggle to swap with main camera. Synthetic radar render (no raw downward camera frames per MSDK privacy)
- 📝 **Single-target detection** (last) — pick highest-conf box nearest center, suppress rest

## Burned cycles (avoid repeating)

- 2026-04-29: 4-hour spiral on detection model returning all-zero outputs. Root cause: wrong TFLite file (raw 2-output model, not post-processed 4-output). See `~/.claude/projects/.../memory/project_detection_model.md`.
- 2026-04-29: GPS read 0 outdoors. Root cause: 3-arg `listen()` only fires on changes. MSDK has 4-arg overload with `getValueOnSubscribe=true`. Fixed v1.7.
