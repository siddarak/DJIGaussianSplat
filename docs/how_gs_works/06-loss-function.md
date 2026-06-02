# 6 — The loss function

How we measure "how wrong is this render?" in a single number, so training has something to push down. Two ingredients: L1 (per-pixel error) and D-SSIM (structural error).

[← overview](../HOW_GS_WORKS.md) · prev: [Color & spherical harmonics](05-color-spherical-harmonics.md) · next: [Gradient descent](07-gradient-descent.md)

---

## 6.1 The setup

We have a real photo taken from a known camera position. We render the current blobs from that same position. Now compare the two images. The **loss** is one number: bigger = more different, smaller = closer match. Training's only job is to make this number small.

## 6.2 L1 — average per-pixel error

The simplest measure: for every pixel, how far off is the rendered color from the real color? Take the absolute difference (so over- and under-shooting both count as error), then average over all pixels.

```
L1 = average over pixels of | rendered_pixel − real_pixel |
```

### Worked numbers

Pretend an image is just 3 grayscale pixels (values 0–1):

| Pixel | Rendered | Real | \|difference\| |
|---|---|---|---|
| 1 | 0.5 | 0.6 | 0.1 |
| 2 | 0.2 | 0.4 | 0.2 |
| 3 | 0.9 | 0.9 | 0.0 |

```
L1 = (0.1 + 0.2 + 0.0) / 3 = 0.1
```

So on average each pixel is `0.1` off. If the render were perfect, every difference is `0` and `L1 = 0`.

## 6.3 Why L1 alone isn't enough

L1 looks at each pixel **in isolation**. It doesn't care about *patterns*. Two renders can have the same L1 but look very different to a human:
- one is slightly wrong everywhere but sharp,
- the other is blurry, smearing edges.

L1 can be fooled into preferring a **blurry average** that's "a little wrong everywhere" over a crisp image that's "right in most places, off in a few." We want sharp edges and correct structure, so we add a second term that judges *structure*.

## 6.4 D-SSIM — structural error

**SSIM** (Structural Similarity) compares two images over small patches, checking three things in each patch:
- **luminance** — similar brightness?
- **contrast** — similar light-to-dark spread?
- **structure** — do the patterns/edges line up?

SSIM ranges from `0` (totally different) to `1` (identical). Since loss should be *small when good*, we use:

```
D-SSIM = 1 − SSIM
```

So `D-SSIM = 0` means structurally identical; bigger means more structural mismatch. This term punishes blurriness and misplaced edges in a way per-pixel L1 cannot.

## 6.5 Combining them

The full 3DGS loss mixes the two:

```
Loss = (1 − λ) · L1  +  λ · D-SSIM
```

`λ` (lambda) is a small mixing fraction; the original paper uses `λ = 0.2`. So roughly 80% "match each pixel's color" + 20% "match the structure." Together they push the render to be both color-accurate **and** sharp.

### Worked combine

Say for an image `L1 = 0.1` and `D-SSIM = 0.3`, with `λ = 0.2`:

```
Loss = 0.8 · 0.1 + 0.2 · 0.3 = 0.08 + 0.06 = 0.14
```

That single `0.14` is what training tries to shrink.

## 6.6 Over the whole training set

This is computed not for one photo but across **all** the captured views (the app's orbit gives many). Each training step picks a camera, renders it, measures this loss, and uses it to improve the blobs (topic 7). Averaged over many views, minimizing the loss forces the blobs to be consistent **from every angle at once** — which is what makes the final splat look right as you fly around it.

---

## Recap

- The loss turns "how wrong is the render" into one number to minimize.
- **L1** = average absolute per-pixel color error. Simple, but blind to structure.
- **D-SSIM = 1 − SSIM** = structural mismatch (luminance, contrast, edges). Punishes blur.
- **Loss = (1−λ)·L1 + λ·D-SSIM**, `λ ≈ 0.2` — color accuracy plus sharpness.
- Computed across all captured views, so the blobs must satisfy every angle.

Next: given this loss, how the blobs actually get adjusted — gradient descent, with a tiny worked example.
