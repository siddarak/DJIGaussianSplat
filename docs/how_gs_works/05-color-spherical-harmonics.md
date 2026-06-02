# 5 — Color & spherical harmonics

Why a blob's color isn't a single value but a small "recipe" that changes with the viewing angle, and how spherical harmonics (SH) store that recipe.

[← overview](../HOW_GS_WORKS.md) · prev: [Alpha blending & sorting](04-alpha-blending-and-sorting.md) · next: [The loss function](06-loss-function.md)

---

## 5.1 Why color depends on viewing angle

A matte wall looks the same color from any angle. But most surfaces don't: a shiny tabletop has a bright highlight that **moves as you move**, a car's paint shifts, a glossy leaf glints. That's because reflected light depends on where you're looking from.

So a single RGB triple per blob isn't enough. Each Gaussian needs color that is a **function of view direction**: "from this angle I look like *this* color; from that angle, slightly different."

## 5.2 The problem: storing a function over all directions

A view direction is a point on a sphere (you can look at the blob from anywhere around it). So we need to store a color for *every direction on a sphere* — that's infinitely many directions. We can't store infinitely many numbers.

The fix is the same trick used for sound and images: build the function out of a few **fixed basis patterns**, and store only **how much of each** you need.

## 5.3 The recipe analogy

Think of a recipe:
- The **ingredients are fixed and known to everyone** (flour, sugar, eggs…).
- A specific cake is just **how much of each ingredient** you use.

Spherical harmonics are the "ingredients" for functions on a sphere:
- The **SH basis functions `Y₀, Y₁, Y₂, …`** are fixed, universal patterns over the sphere. `Y₀` is a flat constant (same everywhere). The next ones are gentle gradients (bright on one side, dim on the other). Later ones are finer, wavier patterns.
- A blob's color is stored as **coefficients `c₀, c₁, c₂, …`** — how much of each pattern to mix.

To get the color in a particular direction `d`, you mix:

```
color(d) = c₀·Y₀(d) + c₁·Y₁(d) + c₂·Y₂(d) + ...
```

The `Yₖ(d)` are computed from the direction (fixed formulas); the `cₖ` are what each Gaussian stores and what training tunes.

## 5.4 Degrees = how much angular detail

SH come in **degrees**, like zoom levels of angular detail:

| Degree | New patterns | What it captures |
|---|---|---|
| 0 | 1 | a flat, view-independent color (pure matte) |
| 1 | 3 | broad direction shading (one side brighter) |
| 2 | 5 | smoother lobes |
| 3 | 7 | finer angular variation (sharper highlights) |

Counts add up: degree-3 total = 1 + 3 + 5 + 7 = **16 patterns** per color channel. With 3 channels (R, G, B) that's **16 × 3 = 48 coefficients** per Gaussian — the "up to 48" from the overview.

- **Low degree** (just `c₀`) = a plain matte color, same from all sides.
- **Higher degrees** = the color can shift with viewpoint, letting blobs fake shiny highlights and subtle sheen.

This is the same idea as harmonics in music (a sound = sum of pure tones) or frequency components in an image — just spread over a sphere of directions instead of over time or across a picture.

## 5.5 Where it sits in the pipeline

When rendering a frame (topic 4), for each blob the renderer:
1. Computes the direction from the blob to the camera.
2. Evaluates `color(d)` by mixing the stored `cₖ` with the fixed `Yₖ(d)`.
3. Feeds that color into the alpha-blend with the blob's opacity.

During training (topics 6–7), the `cₖ` are adjusted along with position, scale, rotation, and opacity, so each blob learns not just *where and what shape* it is, but *how its color should shift with angle*.

---

## Recap

- Real surfaces change color with viewing angle, so each blob stores color as a **function of direction**, not one value.
- That function is built from fixed **spherical-harmonic patterns** `Yₖ`; the blob stores only the **mix amounts** `cₖ`.
- `color(d) = Σ cₖ·Yₖ(d)`. Degree 0 = flat color; higher degrees add angular detail (highlights).
- Degree-3 = 16 patterns × 3 channels = 48 numbers per Gaussian.

Next: once we can render a frame, how do we measure how wrong it is compared to the real photo?
