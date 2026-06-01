# Reconstruction — from capture to splat

This app is the **front half** of the pipeline: it captures posed video around an object. The **back half** turns that into a 3D Gaussian Splat. The back half lives in two private playground repos we control, forked from the upstream research code.

**See also:** [README.md](README.md) · [SUMMER_GOALS.md](SUMMER_GOALS.md) · [DEV_LOG.md](DEV_LOG.md)

---

## The two playgrounds

| Repo | Forked from | Purpose |
|---|---|---|
| [siddarak/gaussian-splatting](https://github.com/siddarak/gaussian-splatting) (private) | [graphdeco-inria/gaussian-splatting](https://github.com/graphdeco-inria/gaussian-splatting) | Standard 3DGS training. Where we wire our drone capture (frames + poses) into the reference trainer. |
| [siddarak/sort-free-gs](https://github.com/siddarak/sort-free-gs) (private) | [LiYukeee/sort-free-gs](https://github.com/LiYukeee/sort-free-gs) | Sort-free / weighted-sum rendering. Mobile-friendly path toward on-device splat preview. |

Both are private mirrors (full history). Upstream `.gitmodules` still point to the public upstreams, so submodules (`diff-gaussian-rasterization`, `simple-knn`, SIBR viewer) resolve normally on clone.

---

## How a capture becomes a splat

```
[this app]   fly orbit → record MP4 (+ per-frame camera poses from marker survey)
                 │
                 ▼  ffmpeg: MP4 → frame images
[playground] convert marker poses → COLMAP format (cameras.txt / images.txt / points3D.txt)
                 │                                  └─ OR run COLMAP SfM if no poses
                 ▼
[gaussian-splatting] train ~30k iters → object.ply (the splat)
                 │
                 ▼
[viewer]     desktop SIBR viewer now  →  sort-free on-device preview (future)
```

---

## How 3D Gaussian Splatting works (quick reference)

A splat is an explicit cloud of **3D Gaussians** (no neural network, no mesh). Each Gaussian has:

- **position** (3D center)
- **covariance** (ellipsoid shape + orientation, stored as rotation quaternion + scale)
- **opacity**
- **color** (view-dependent, spherical-harmonic coefficients)

A scene is 1–5 million of these. Training:

1. Initialize Gaussians from a sparse point cloud (from COLMAP).
2. Project each 3D Gaussian to a 2D "splat" on screen.
3. **Sort splats by depth** per view, then alpha-blend front-to-back.
4. Compare the render to the real photo (L1 + D-SSIM loss).
5. Gradient descent on all Gaussian parameters.
6. Adaptive density control — clone / split / prune Gaussians.
7. Repeat ~30k iterations.

**Two facts that matter for this project:**

1. **It needs posed images.** Poses normally come from COLMAP structure-from-motion, which is slow and can fail on textureless/repetitive scenes. Our marker survey produces absolute camera poses directly — feeding those in shortcuts or skips COLMAP. This is the project's research angle.
2. **The depth sort is the bottleneck** and the cause of "popping" flicker. Sort-free GS (the second playground) replaces ordered alpha-blending with an order-independent weighted sum — no sort, no popping, ~1.23× faster on mobile GPUs. That's the rendering path that makes on-device preview viable.

---

## What we experiment with (per playground)

**gaussian-splatting fork**
- `playground/drone/` (to be added): MP4 → frames extractor, marker-pose → COLMAP converter, end-to-end run script
- Goal: prove a drone capture produces a clean splat, and that marker poses can substitute for COLMAP SfM

**sort-free-gs fork**
- Mobile rendering path experiments
- Goal: a renderer that can preview a captured splat on the same phone

---

## Status

- ✅ Both private playgrounds created (mirrors of upstream)
- 📝 Drone data adapters (frame extractor, pose converter) — not yet added
- 📝 First end-to-end capture → splat run — not yet done
