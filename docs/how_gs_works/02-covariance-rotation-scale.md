# 2 — Covariance, rotation & scale

How the three numbers that describe a stretched, tilted egg are actually stored: `Σ = R·S·Sᵀ·Rᵀ`. Worked with real numbers.

[← overview](../HOW_GS_WORKS.md) · prev: [Gaussians & ellipses](01-gaussians-and-ellipses.md) · next: [Projection 3D→2D](03-projection-to-screen.md)

---

## 2.1 What the covariance matrix holds

In 2D the covariance is a 2×2 grid:

```
Σ = | a   b |
    | b   c |
```

- `a` = spread² along x (so the horizontal half-width is √a)
- `c` = spread² along y (vertical half-width √c)
- `b` = the **tilt / correlation**. If `b = 0`, the ellipse lines up with the x–y axes. If `b ≠ 0`, it leans diagonally.

Note it's **symmetric** (same `b` top-right and bottom-left). Covariance matrices are always symmetric.

## 2.2 Why not just store a, b, c directly?

Because random `a, b, c` can describe an **impossible** ellipse. Example: `a = 1, c = 1, b = 5`. That says "barely any spread along x or y, but enormous diagonal correlation" — there's no real ellipse like that. Mathematically it fails a rule called *positive semi-definiteness* (it would need negative width in some direction).

During training the optimizer nudges numbers freely. If it nudged `a, b, c` directly, it would constantly create impossible ellipses. So instead they store two safe ingredients and **build** `Σ` from them — guaranteeing a valid ellipse every time.

## 2.3 The two ingredients: scale and rotation

```
Σ = R · S · Sᵀ · Rᵀ
```

- **`S` = scale.** A diagonal matrix holding the stretch along each axis. In 2D, `S = diag(s₁, s₂)`. Start with a unit circle and stretch it to half-widths `s₁`, `s₂`.
- **`R` = rotation.** Rotates the stretched shape to its final tilt.

Plain-English recipe:

> **unit ball → stretch (S) → rotate (R) → your egg.**

Because you only ever *stretch a real ball and rotate it*, you can never produce an impossible shape. That's the whole point of this form.

## 2.4 Worked example — axis-aligned

Take `s₁ = 2`, `s₂ = 1`, rotation angle `θ = 0` (no tilt). With `θ = 0`, `R` is the identity (does nothing), so:

```
Σ = S·Sᵀ = | 2  0 | · | 2  0 |  =  | 4  0 |
            | 0  1 |   | 0  1 |     | 0  1 |
```

Read it off: `a = 4` → half-width √4 = 2 in x. `c = 1` → half-width 1 in y. `b = 0` → no tilt. A horizontal ellipse, twice as wide as tall. Matches what topic 1 called an axis-aligned ellipse.

## 2.5 Worked example — tilted 45°

Same stretches `s₁ = 2`, `s₂ = 1`, but now rotate `θ = 45°`. The rotation matrix:

```
R = | cos45  −sin45 |  =  | 0.707  −0.707 |
    | sin45   cos45 |     | 0.707   0.707 |
```

Carry out `Σ = R · (S·Sᵀ) · Rᵀ` with `S·Sᵀ = diag(4, 1)`:

```
Σ = | 2.5   1.5 |
    | 1.5   2.5 |
```

Read it off:
- `a = 2.5`, `c = 2.5` — equal spread in x and y now, because a 45°-tilted ellipse looks equally wide and tall when measured along the screen axes.
- `b = 1.5` — **non-zero, so it's tilted.** The sign of `b` tells you which diagonal it leans along (positive = lower-left to upper-right).

The ellipse's *own* axes are still stretched 2 and 1 — those are hidden inside, recoverable as the matrix's "eigen-directions" (just a fancy name for "the ellipse's own long and short axes"). You rarely need to recover them; you store `S` and `R` and rebuild `Σ` whenever you draw.

## 2.6 The `Σ⁻¹` in the Gaussian formula

Recall the blob value uses the **inverse**:

```
G(p) = exp( −½ · (p − μ)ᵀ · Σ⁻¹ · (p − μ) )
```

Why the inverse? Because you want distance measured **in units of the ellipse's own spread**. A point that's 2 units away along a direction where the spread is 2 should count as "1 spread away" — same brightness as a point 1 unit away in a direction where the spread is 1. `Σ⁻¹` does exactly that rescaling: it converts raw distance into "how many σ away am I, accounting for stretch and tilt." That rescaled distance is called the **Mahalanobis distance**; the blob's brightness depends only on it.

## 2.7 In 3D

Same machinery, one size up:
- `S = diag(s₁, s₂, s₃)` — three stretches.
- `R` = a 3D rotation, stored as a **quaternion** (4 numbers). A quaternion is just a compact, glitch-free way to write "rotate by some angle about some axis" — safer for optimization than three separate angles (which can jam, a problem called gimbal lock).
- `Σ = R·S·Sᵀ·Rᵀ` is a 3×3 symmetric matrix (6 unique numbers) describing the egg.

So each Gaussian stores **3 scale numbers + 4 quaternion numbers**, and the renderer rebuilds the 3×3 `Σ` from them on the fly.

---

## Recap

- `Σ` encodes width, height, tilt of the ellipse/egg.
- Storing it as `R·S·Sᵀ·Rᵀ` (rotate a stretched ball) guarantees the shape is always valid while training nudges the numbers.
- Diagonal entries = spread² along screen axes; off-diagonals = tilt.
- `Σ⁻¹` in the blob formula rescales distance into "number of σ," so brightness is consistent in every direction.

Next: how the 3D egg is cast onto the flat screen.
