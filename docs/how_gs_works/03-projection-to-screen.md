# 3 — Projecting 3D → 2D screen

How a 3D egg becomes a 2D ellipse on the screen. Perspective (the part you know from similar triangles) plus a gentle look at the "Jacobian" (the part that transforms the egg's shape).

[← overview](../HOW_GS_WORKS.md) · prev: [Covariance, rotation & scale](02-covariance-rotation-scale.md) · next: [Alpha blending & sorting](04-alpha-blending-and-sorting.md)

---

## 3.1 Perspective: far things look smaller

A camera sits at the origin looking down its Z axis. A 3D point in front of it has coordinates `(X, Y, Z)`, where `Z` is the depth (distance straight ahead). It lands on the screen at:

```
x = f · X / Z
y = f · Y / Z
```

`f` is the **focal length** in pixels (a property of the lens + sensor; for the Mini 4 Pro at 1080p it's roughly 1300). This is just **similar triangles**: an object twice as far away (double `Z`) appears half as big. The divide-by-`Z` is what makes perspective work — and it's the only "hard" part, because dividing by a coordinate is **not** a straight-line (linear) operation.

### Worked numbers

`f = 1000`. A point at `(X, Y, Z) = (1, 0, 5)`:

```
x = 1000 · 1 / 5 = 200      y = 1000 · 0 / 5 = 0
```

Move it twice as far, `Z = 10`:

```
x = 1000 · 1 / 10 = 100     y = 0
```

Same real point, farther away → lands closer to the screen center → appears smaller. Exactly what you'd expect.

## 3.2 The center is easy; the shape is the work

Projecting the egg's **center** `μ` is just the formula above. But we also need the egg's **shape** (`Σ`) on screen — does the projected ellipse come out fat or thin, tilted which way? The shape gets distorted by perspective: an egg off to the side of the frame, or close to the camera, projects differently than one dead center.

The problem: the divide-by-`Z` is curvy (nonlinear), and our whole toolkit (topic 2) only knows how to transform shapes with **straight-line** operations (matrices). So we make a **local straight-line approximation** of the curvy projection, right at the egg's center.

## 3.3 The Jacobian = "the local stretch-and-skew"

Zoom way in on the projection near one egg's center. Over that tiny region, the curvy perspective map looks almost flat — almost like a simple stretch + skew. That local stretch-and-skew, written as a small grid of slopes, is called the **Jacobian** `J`.

Analogy: the Earth is round, but a street map of your block treats it as flat and gets it nearly right, because over a small patch the curvature doesn't matter. `J` is the "flat street map" of the perspective projection, valid in the small neighborhood of one egg.

Concretely `J` is a 2×3 grid holding "if I nudge X (or Y or Z) a little, how much do screen `x` and `y` move?" Those nudge-ratios are slopes; near the center they fully capture how the projection stretches and skews the shape.

## 3.4 Putting it together

To get the on-screen 2D covariance `Σ'`, the 3D covariance `Σ` is passed through two transforms:

```
Σ' = J · W · Σ · Wᵀ · Jᵀ
```

- `W` rotates the egg from world coordinates into the camera's point of view (where the camera looks down Z).
- `J` applies the local perspective stretch-and-skew.

You don't compute this by hand — the takeaway is the **pattern**: to move a shape `Σ` into a new coordinate system through a transform `M`, you sandwich it as `M · Σ · Mᵀ`. (Same `R · Σ · Rᵀ` pattern from topic 2, twice.) The result `Σ'` is a 2×2 covariance — a 2D ellipse on the screen.

## 3.5 The payoff: a projected Gaussian is still a Gaussian

After all that, the 3D egg's "shadow" on the screen is a **2D ellipse-blob** — another Gaussian, with center `(x, y)` from §3.1 and covariance `Σ'` from §3.4. This is the property that makes the whole method tractable: projection keeps us in the Gaussian family, so the next step (blending overlapping blobs) uses the same simple math everywhere.

Each screen blob now has:
- a 2D center (where it lands),
- a 2D shape `Σ'` (how the ellipse looks),
- a depth `Z` (kept aside — needed for ordering in topic 4),
- an opacity and a color (topics 1 and 5).

---

## Recap

- Perspective projection: `x = fX/Z`, `y = fY/Z` — similar triangles, far = small.
- The divide-by-`Z` is curvy, so the egg's *shape* can't be transformed by a plain matrix directly.
- The **Jacobian** `J` is a local straight-line approximation of that curvy map — the "flat street map" near one egg.
- On-screen shape: `Σ' = J·W·Σ·Wᵀ·Jᵀ` (sandwich pattern). A 3D egg projects to a 2D ellipse-blob.

Next: many blobs overlap one pixel — how their colors combine, and why that forces a depth sort.
