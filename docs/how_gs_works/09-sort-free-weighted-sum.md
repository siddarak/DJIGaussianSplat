# 9 — Sort-free weighted-sum rendering

The one equation that removes the depth sort, why it works, what it costs, and why it matters for previewing splats on a phone. Based on *Sort-free Gaussian Splatting via Weighted Sum Rendering* (Hou et al., ICLR 2025, [arxiv:2410.18931](https://arxiv.org/abs/2410.18931)) — the second playground repo.

[← overview](../HOW_GS_WORKS.md) · prev: [Adaptive density control](08-adaptive-density.md)

---

## 9.1 Recap of the villain

From topic 4, standard blending is:

```
C = Σᵢ cᵢ · αᵢ · Tᵢ        Tᵢ = (1−α₁)(1−α₂)…(1−α₍ᵢ₋₁₎)
```

`Tᵢ` is a **product over everything in front** of blob `i`. "In front" requires depth order, so every frame the renderer **sorts** all blobs. That sort is slow on phones and causes **popping** when order flips. We want to keep good-looking results but drop the sort.

## 9.2 The key observation: addition doesn't care about order

The reason order matters is that `Tᵢ` is a **running product down an ordered stack**. But plain **addition is order-independent**:

```
3 + 5 + 2  =  2 + 5 + 3  =  10
```

So if we could express the pixel color as a **plain sum** of per-blob contributions — with no "what's in front" product — the order wouldn't matter and no sort would be needed.

## 9.3 The fix: a weighted average

Replace the ordered stack with a **weighted average** of the blobs over the pixel:

```
        Σᵢ  wᵢ · cᵢ
C  =  ───────────────
          Σᵢ  wᵢ
```

- `cᵢ` = blob color
- `wᵢ` = a **weight** for blob `i`, built from its opacity and its depth: `wᵢ = αᵢ · g(zᵢ)`, where `g(z)` gives **nearer blobs a bigger weight**.

This is exactly a **weighted grade average**:

```
final grade = (test₁·w₁ + test₂·w₂ + …) / (w₁ + w₂ + …)
```

You can add the courses in any order and get the same grade. Same here: the contributions are **summed**, so their order is irrelevant — **no sort**.

Note the weight still *uses* depth through `g(z)`, so a nearer blob can dominate (an approximation of occlusion). What's gone is the **ordered product** — depth now enters as a per-blob weight, not as a front-to-back chain.

## 9.4 Worked example — order independence

Three blobs over a pixel. Colors and weights (weights already fold in opacity + depth):

| Blob | color `cᵢ` | weight `wᵢ` |
|---|---|---|
| A | red `(1,0,0)` | 0.5 |
| B | green `(0,1,0)` | 0.3 |
| C | blue `(0,0,1)` | 0.1 |

Weighted average:

```
numerator = 0.5·(1,0,0) + 0.3·(0,1,0) + 0.1·(0,0,1) = (0.5, 0.3, 0.1)
denominator = 0.5 + 0.3 + 0.1 = 0.9
C = (0.5, 0.3, 0.1) / 0.9 = (0.556, 0.333, 0.111)
```

Now shuffle the order to C, A, B:

```
numerator = 0.1·(0,0,1) + 0.5·(1,0,0) + 0.3·(0,1,0) = (0.5, 0.3, 0.1)
denominator = 0.1 + 0.5 + 0.3 = 0.9
C = (0.556, 0.333, 0.111)   ← identical
```

Same pixel, any order. Compare this to topic 4, where reordering flipped `(0.5,0.25,0.25)` into pure blue. **That** is the whole win: order no longer changes the answer, so the sort can be deleted.

## 9.5 What you give up

A weighted average is **not** physically the same as true front-to-back glass stacking. In real alpha blending, a solid blob in front should completely hide what's behind it; a weighted sum only *down-weights* the back blob, it doesn't fully block it. So out of the box, sort-free renders look subtly wrong (slightly washed-out where there should be hard occlusion).

The fix: **retrain the Gaussians with the weighted-sum renderer in the loop** (topics 6–7, but using this blend). The optimizer adjusts opacities, colors, and shapes so the *approximate* renderer produces correct-looking images. The model is co-designed with its renderer. After this re-optimization, quality is competitive with sorted GS on standard benchmarks.

## 9.6 What you gain

From the paper:
- **No sort** → about **1.23× faster** rendering on mobile GPUs (and simpler GPU code — no big sorting pass).
- **No order swaps** → **no popping**; smooth as the camera moves.
- Mobile-friendly: the heavy, irregular sort is exactly what phone GPUs handle worst, so removing it helps them most.

## 9.7 Why this is in our project

Our north-star future feature is **previewing the captured splat on the same phone that captured it** — instant operator feedback in the field. Standard GS makes that hard because of the per-frame sort. Sort-free rendering is the path that makes an on-device viewer realistic, which is why [siddarak/sort-free-gs](https://github.com/siddarak/sort-free-gs) is a separate playground (see [../RECONSTRUCTION.md](../RECONSTRUCTION.md)).

---

## Recap

- Standard blending needs an **ordered product** (`Tᵢ`) → forces a per-frame sort → slow on mobile, causes popping.
- Sort-free replaces it with a **weighted average**, `C = Σ wᵢcᵢ / Σ wᵢ`, where `wᵢ = αᵢ·g(zᵢ)`.
- Because it's a **sum**, order doesn't matter (worked: any order → same pixel) → **no sort**.
- Cost: it's an approximation of true occlusion → **retrain the blobs to the new renderer** so quality holds.
- Gain: ~1.23× faster on mobile, no popping — the rendering path for on-device splat preview.

[← back to overview](../HOW_GS_WORKS.md)
