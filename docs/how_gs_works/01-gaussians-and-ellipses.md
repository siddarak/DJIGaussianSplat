# 1 — Gaussians & ellipses

From the bell curve you've seen in school to the 3D "egg" that is one Gaussian splat. No calculus. Just functions and a bit of coordinate geometry.

[← back to overview](../HOW_GS_WORKS.md) · next: Covariance, rotation & scale *(pending)*

---

## 1.1 The 1D bell curve

A Gaussian in one dimension:

```
G(x) = exp( −(x − μ)² / (2σ²) )
```

Three pieces:

- `exp(z)` means `e^z`, where `e ≈ 2.718`. The only facts you need about it here: `exp(0) = 1`, and as `z` becomes more negative, `exp(z)` shrinks smoothly toward 0 (never reaching it). So `exp` of "a negative number" is "a number between 0 and 1."
- `μ` (mu) is the **center** — where the peak is.
- `σ` (sigma) is the **width** — how spread out the bump is.

### Why this shape

Look at the inside: `−(x − μ)² / (2σ²)`.

- `(x − μ)²` is the distance from the center, squared. It is `0` at the center and grows as you move away. Squaring makes it symmetric (left and right of center behave the same) and always positive.
- The minus sign flips it negative.
- `exp(negative)` turns that into a value that is **1 at the center** and **fades toward 0** as you move away.

### Worked numbers

Take `μ = 0`, `σ = 1`. Then `G(x) = exp(−x²/2)`.

| x | x²/2 | G(x) = exp(−x²/2) |
|---|---|---|
| 0 | 0 | 1.000 |
| 1 | 0.5 | 0.607 |
| 2 | 2.0 | 0.135 |
| 3 | 4.5 | 0.011 |

So at one σ away from center the value is ~0.61, at two σ ~0.14, at three σ ~0.01. That is the bell shape: tall in the middle, tails fading fast. **σ controls how fast it fades** — bigger σ = wider, gentler bump.

---

## 1.2 Two dimensions — a round blob

Now a point is `(x, y)`. The simplest 2D Gaussian:

```
G(x, y) = exp( −[ (x − μx)² + (y − μy)² ] / (2σ²) )
```

The inside is just the **squared distance from the center** `(μx, μy)` (Pythagoras: `distance² = dx² + dy²`). So the value depends only on *how far* you are from the center, not the direction. That makes a **round blob**: bright in the middle, fading equally in all directions.

### A neat fact: it splits into x and y

Because `exp(a + b) = exp(a)·exp(b)`, the round blob factors:

```
G(x,y) = exp(−dx²/2σ²) · exp(−dy²/2σ²)
         └── bell in x ──┘   └── bell in y ──┘
```

It's literally a bell curve along x multiplied by a bell curve along y. Useful intuition: a 2D Gaussian is two 1D Gaussians acting together.

---

## 1.3 Stretch it — an axis-aligned ellipse

A round blob spreads the same in every direction. Real detail needs **stretched** blobs. Give x and y their own widths, `σx` and `σy`:

```
G(x, y) = exp( −[ (x−μx)²/(2σx²) + (y−μy)²/(2σy²) ] )
```

Example: `σx = 2`, `σy = 1`. The blob fades slowly in x (wide) and quickly in y (short) → a **horizontal ellipse**.

### Contour lines are ellipses

Ask: "where is the blob's value exactly 0.607 (the one-σ level)?" Setting the formula equal to that constant gives:

```
(x−μx)²/σx²  +  (y−μy)²/σy²  =  1
```

That is exactly the school equation of an **ellipse** with half-widths `σx` and `σy`. So a Gaussian is "a smooth hill whose level-lines are nested ellipses." The blob *is* an ellipse, just fuzzy instead of a hard outline.

---

## 1.4 Tilt it — the diagonal ellipse

So far the ellipse axes line up with the x and y axes. Real blobs can point diagonally (the edge of a tilted leaf). To tilt, you need a **cross term** that mixes x and y — something with `(x−μx)(y−μy)` in it.

Once you allow that cross term, writing the formula out gets messy. The clean way to carry "stretch in x, stretch in y, **and** a tilt" is to pack those three facts into a small 2×2 grid of numbers called the **covariance matrix `Σ`**, and write:

```
G(p) = exp( −½ · (p − μ)ᵀ · Σ⁻¹ · (p − μ) )
```

Here `p = (x, y)`. You don't need to evaluate that by hand — the takeaway is:

> **`Σ` is the three numbers that describe the ellipse: its width, its height, and its tilt.**

That matrix is the whole subject of deep-dive #2.

---

## 1.5 Three dimensions — the egg

Everything carries over. A point is `(x, y, z)`. The blob becomes a 3D **ellipsoid** — an egg shape — that can be stretched differently along three directions and tilted any way in space. Its shape is described by a 3×3 covariance matrix `Σ` (6 unique numbers).

One such fuzzy, stretched, tilted egg — plus a color and an opacity — is **one Gaussian splat**. A whole scene is millions of them.

---

## 1.6 Why Gaussians specifically?

Three properties make them the right building block:

1. **Smooth everywhere.** No hard edges. That smoothness is what lets training nudge them gently (deep-dive #7 needs everything smooth).
2. **A projected Gaussian is still a Gaussian.** When you cast the 3D egg onto the 2D screen, the shadow is again an ellipse-blob (deep-dive #3). The math stays in the same family — very convenient.
3. **Compact.** A handful of numbers (center, 3 scales, 4 rotation, opacity, color) describe a soft 3D shape. Cheap to store, cheap to blend.

---

## Recap

- A Gaussian is a smooth bump: peak `1` at its center, fading toward `0` outward.
- `σ` (or in 2D/3D, the covariance `Σ`) controls **how wide, how tall, and how tilted** the bump is.
- Its level-lines are ellipses (2D) or ellipsoids (3D) — so a Gaussian *is* a fuzzy ellipse/egg.
- One splat = one 3D egg + opacity + color. A scene = millions of them.

Next: how `Σ = R·S·Sᵀ·Rᵀ` encodes the egg's stretch and tilt, with worked numbers.
