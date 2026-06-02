# How Gaussian Splatting Works

A from-scratch explanation of 3D Gaussian Splatting (3DGS) and the sort-free variant, assuming only high-school math. This top file is the **overview**. Each topic has a deeper write-up in [`how_gs_works/`](how_gs_works/) with worked examples.

**Related:** [../RECONSTRUCTION.md](../RECONSTRUCTION.md) (how our drone capture feeds into this) · the two playground repos linked there.

---

## The whole thing in one breath

A scene is represented as **millions of tilted, colored, see-through eggs** (3D Gaussians). To draw a frame: project each egg onto the screen (it becomes a soft 2D ellipse), then stack the overlapping ellipses like panes of colored glass, front to back. The glass-stacking needs depth order, so the blobs get **sorted** every frame. To *learn* the eggs: render, compare to real photos, nudge every egg's numbers slightly downhill to reduce error, and repeat tens of thousands of times. **Sort-free GS** swaps the ordered glass-stack for an order-independent weighted average, so no sort is needed — then retrains the eggs so the approximation still looks right.

---

## The pipeline at a glance

```
posed photos → init Gaussians from sparse point cloud
      ↓
  project each 3D Gaussian → 2D ellipse on screen
      ↓
  SORT by depth, then alpha-blend front-to-back     ← sort-free GS changes this step
      ↓
  compare render to real photo (L1 + D-SSIM loss)
      ↓
  gradient descent on every Gaussian parameter
      ↓
  adaptive density: clone / split / prune
      ↓
  repeat ~30k iterations → final splat (.ply)
```

---

## What each Gaussian stores

| Property | Numbers | Meaning |
|---|---|---|
| position `μ` | 3 | where the egg sits in 3D |
| scale `S` | 3 | how stretched along its own axes |
| rotation `R` | 4 (quaternion) | how the egg is tilted |
| opacity `α` | 1 | 0 = invisible, 1 = solid |
| color (SH) | up to 48 | view-dependent color recipe |

Position + scale + rotation together define the egg's shape via the covariance `Σ = R·S·Sᵀ·Rᵀ`.

---

## Deep dives (read in order)

Each is a standalone file in [`how_gs_works/`](how_gs_works/). Written one at a time.

1. [Gaussians & ellipses](how_gs_works/01-gaussians-and-ellipses.md) — from the bell curve to a 3D blob
2. [Covariance, rotation & scale](how_gs_works/02-covariance-rotation-scale.md) — what `Σ = R·S·Sᵀ·Rᵀ` means, with numbers
3. [Projecting 3D → 2D screen](how_gs_works/03-projection-to-screen.md) — perspective + the Jacobian, gently
4. [Alpha blending & why sorting is needed](how_gs_works/04-alpha-blending-and-sorting.md) — the compositing equation, worked
5. [Color & spherical harmonics](how_gs_works/05-color-spherical-harmonics.md) — view-dependent color
6. [The loss function](how_gs_works/06-loss-function.md) — L1 + D-SSIM
7. [Gradient descent](how_gs_works/07-gradient-descent.md) — a tiny worked example
8. [Adaptive density control](how_gs_works/08-adaptive-density.md) — clone / split / prune
9. [Sort-free weighted-sum rendering](how_gs_works/09-sort-free-weighted-sum.md) — the one equation that removes the sort

---

## Why this matters for our project

Our drone app captures posed video around an object. That feeds the training pipeline above (see [../RECONSTRUCTION.md](../RECONSTRUCTION.md)). Two specific ties:

- **Poses:** 3DGS needs to know each photo's camera position. Normally that comes from COLMAP structure-from-motion (slow, can fail). Our marker survey produces poses directly — the research angle is using those to shortcut COLMAP.
- **Mobile preview:** the depth sort is the part a phone struggles with. Sort-free rendering (topic 9) is what could let us preview a captured splat on the same phone.
