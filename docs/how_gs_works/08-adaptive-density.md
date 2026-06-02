# 8 — Adaptive density control

Training doesn't just move existing blobs — it **adds** blobs where detail is missing and **removes** blobs that aren't helping. This is how the number of Gaussians grows and shrinks to fit the scene.

[← overview](../HOW_GS_WORKS.md) · prev: [Gradient descent](07-gradient-descent.md) · next: [Sort-free weighted-sum rendering](09-sort-free-weighted-sum.md)

---

## 8.1 Why you can't fix the blob count

You start with a sparse point cloud (from COLMAP) — maybe ~100,000 points, one blob each. But scenes have wildly uneven detail: a blank wall needs almost no blobs, while a potted plant's leaves need thousands. If the count were fixed:
- too few → smooth areas fine, but fine detail stays blurry (not enough blobs to represent it),
- too many → wasteful, slow, and prone to noise.

So the count must **adapt** during training. The method runs density control every few hundred steps.

## 8.2 The signal: where is a blob "struggling"?

The clue comes from the slopes (topic 7). If a blob's **position keeps getting large gradients** — training keeps trying to shove it around to fix the image — that area is **under-reconstructed**: one blob is being asked to do a job too big for it. Those high-gradient blobs are the candidates for adding more coverage. Two cases:

### Clone (for small blobs in empty gaps)
If the struggling blob is **small**, the area is *under-populated* — there isn't enough "stuff" there. Fix: **duplicate** the blob (clone it) and nudge the copy along the gradient direction, adding coverage where it's needed.

### Split (for big blobs over detailed areas)
If the struggling blob is **large**, it's a single big blob trying to cover fine detail. Fix: **split** it into two smaller blobs (scale each down by a factor of ~1.6) positioned within the original. Now two finer blobs can capture the detail the big one couldn't.

```
under-reconstructed + small blob  → CLONE  (add coverage)
under-reconstructed + large blob  → SPLIT  (add resolution)
```

## 8.3 Removing blobs: prune

Blobs that aren't pulling their weight get deleted:

- **Low opacity:** if a blob's opacity falls below a small threshold (~0.005), it's nearly invisible and contributes nothing → remove it.
- **Too large / floaters:** blobs that have grown unreasonably big in world space or cover too much screen are also culled, since they tend to be artifacts rather than real surface.

## 8.4 Opacity reset — clearing out floaters

A recurring failure mode is **floaters**: stray semi-transparent blobs hanging in empty space that happen to slightly reduce the loss. To clean them up, every so often training **resets all opacities to a low value**. Real, useful blobs quickly re-earn their opacity through gradient descent (the loss pushes them back up); useless floaters stay low and get pruned at the next cull. It's a periodic "prove you're needed" sweep.

## 8.5 The arc over training

Roughly how the count moves:

```
start:    ~100k blobs (from COLMAP point cloud)
early:    rapid cloning/splitting → count climbs as detail fills in
mid:      pruning + opacity resets trim floaters and dead blobs
late:     density control stops; only fine-tuning of existing blobs
end:      ~1–5 million blobs, distributed by actual scene complexity
```

The result is efficient: dense where the scene is detailed, sparse where it's smooth — without anyone choosing the count by hand.

## 8.6 Tie-in to our project

More captured views and good coverage (the orbit's whole point) give density control better signal about where detail lives. Sparse or one-sided capture leaves some regions under-observed, so the blobs there stay blurry no matter how long you train. This is part of why a **full hemispherical orbit** matters for reconstruction quality — it feeds every region enough views for the blobs to resolve.

---

## Recap

- Blob count isn't fixed; it adapts to scene detail during training.
- High position-gradient = under-reconstructed area → **clone** (if blob small) or **split** (if blob large).
- **Prune** near-invisible or oversized blobs; periodic **opacity reset** flushes floaters.
- Count typically grows from ~100k to 1–5M, concentrated where detail is.
- Good orbit coverage gives density control the views it needs to resolve every region.

Next: the one change that removes the depth sort — sort-free weighted-sum rendering.
