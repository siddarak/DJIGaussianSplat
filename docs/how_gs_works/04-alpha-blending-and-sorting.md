# 4 — Alpha blending & why sorting is needed

How the colors of overlapping blobs combine into one pixel, worked with real numbers — and why this step forces the renderer to sort every frame. This is the step sort-free GS (topic 9) replaces.

[← overview](../HOW_GS_WORKS.md) · prev: [Projection 3D→2D](03-projection-to-screen.md) · next: [Color & spherical harmonics](05-color-spherical-harmonics.md)

---

## 4.1 The stacked-glass picture

Pick one pixel on the screen. Several blobs overlap it, each at a different depth. Think of each as a **pane of colored, partly see-through glass**. You look through the stack from the front (nearest) to the back (farthest).

Each pane has:
- a **color** `c` (what it adds)
- an **opacity** `α` (alpha): `0` = fully clear, `1` = fully solid

A pane with opacity `α`:
- contributes `α` worth of its own color, and
- lets `(1 − α)` of everything behind it pass through.

## 4.2 The blending equation

Blend `N` panes ordered front → back:

```
C = c₁·α₁
  + c₂·α₂·(1−α₁)
  + c₃·α₃·(1−α₁)(1−α₂)
  + ...
```

Compactly:

```
C = Σᵢ cᵢ · αᵢ · Tᵢ        where   Tᵢ = (1−α₁)(1−α₂)…(1−α₍ᵢ₋₁₎)
```

`Tᵢ` is the **transmittance** — "how much light still gets through after all the panes in front of pane `i`." The front pane has nothing ahead of it, so `T₁ = 1`. Each pane deeper in the stack receives less.

## 4.3 Worked example — three panes

Three blobs over one pixel, ordered front → back. Colors as (R, G, B):

| Order | Color | α |
|---|---|---|
| 1 (front) | red `(1, 0, 0)` | 0.5 |
| 2 (middle) | green `(0, 1, 0)` | 0.5 |
| 3 (back) | blue `(0, 0, 1)` | 1.0 |

Transmittances:
- `T₁ = 1`
- `T₂ = (1 − 0.5) = 0.5`
- `T₃ = (1 − 0.5)(1 − 0.5) = 0.25`

Contributions:
- Pane 1: `red · 0.5 · 1   = (0.5, 0, 0)`
- Pane 2: `green · 0.5 · 0.5 = (0, 0.25, 0)`
- Pane 3: `blue · 1.0 · 0.25 = (0, 0, 0.25)`

**Final pixel** `C = (0.5, 0.25, 0.25)` — mostly red, with a bit of green and blue showing through. Makes sense: the half-clear red is in front, so it dominates.

## 4.4 Why order matters — swap the stack

Now put the **blue** pane in front instead (order: blue, green, red):

- Pane 1 = blue, `α = 1.0`, `T₁ = 1` → contributes `blue · 1.0 · 1 = (0, 0, 1)`.
- After a fully solid pane, transmittance becomes `T₂ = (1 − 1.0) = 0`.
- Panes 2 and 3 are multiplied by `0` → they contribute **nothing**.

**Final pixel** `C = (0, 0, 1)` — pure blue.

Same three blobs, **completely different pixel** (`(0.5, 0.25, 0.25)` vs `(0, 0, 1)`), purely because the order changed. A solid blob in front hides everything behind it; the same blob in back is hidden. **Order is not a detail — it determines the result.**

## 4.5 Therefore: sort every frame

To evaluate `Tᵢ` ("everything in front of pane `i`"), the renderer must know the depth order of all blobs over each pixel. So before blending, it **sorts all the Gaussians by depth** relative to the current camera.

This sort is the performance villain:

- **Cost:** millions of blobs, re-sorted for every new viewpoint, every frame. On a desktop GPU it's manageable; on a phone it's the bottleneck.
- **Popping:** when the camera moves slightly and two blobs swap depth order, the pixel can jump suddenly (like our red↔blue flip). Across a moving video that shows up as flicker called **popping**.

## 4.6 The opacity itself comes from the blob

One detail: a blob's `α` at a given pixel isn't a single fixed number — it's the blob's stored peak opacity multiplied by the Gaussian falloff at that pixel (topic 1). Near the blob's center the falloff is ~1 (full opacity); out toward its edge the falloff →0 (transparent). So a blob is solid in its middle and fades at its rim, then that per-pixel `α` feeds the blending above.

---

## Recap

- Each pixel = a stack of colored translucent panes, blended front-to-back.
- `C = Σ cᵢ·αᵢ·Tᵢ`, with `Tᵢ` = product of `(1−α)` for everything in front.
- Because `Tᵢ` depends on "what's in front," **order changes the result** (worked: red-front vs blue-front give totally different pixels).
- So the renderer must **sort by depth every frame** — slow on mobile, and the cause of popping.
- This is exactly the step sort-free GS removes (topic 9).

Next: where each blob's color comes from, and why it changes with viewing angle.
