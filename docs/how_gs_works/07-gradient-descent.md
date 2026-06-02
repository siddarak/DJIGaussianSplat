# 7 — Gradient descent

How millions of blob numbers get adjusted to shrink the loss. The "rolling downhill" idea, with a tiny worked example you can follow by hand.

[← overview](../HOW_GS_WORKS.md) · prev: [The loss function](06-loss-function.md) · next: [Adaptive density control](08-adaptive-density.md)

---

## 7.1 The idea: roll downhill

Picture the loss as a landscape. Every adjustable number (a blob's x-position, a scale, an opacity, a color coefficient) is a direction you can move in. The height of the landscape is the loss. We want the lowest point.

You can't see the whole landscape (it has millions of dimensions). But at your current spot you can feel **which way is downhill and how steep** — then take a small step that way. Repeat. You roll toward a valley bottom. That's gradient descent.

## 7.2 Gradient = slope = "which way is downhill"

A **gradient** is just a slope. For one number `p`, the slope of the loss tells you:
- its **sign**: is the loss going up or down as `p` increases?
- its **size**: how steeply?

Rule for one parameter:

```
new p = old p − (learning rate) × (slope at p)
```

- The **minus** sign means "go opposite the uphill direction" = downhill.
- The **learning rate** (lr) is the step size — a small number like `0.1`. Too big = you overshoot and bounce; too small = painfully slow.

## 7.3 Tiny worked example

Minimize `f(p) = (p − 3)²`. (The lowest point is obviously at `p = 3`, where `f = 0` — but let's let the method find it.)

The slope of `(p − 3)²` is `2·(p − 3)`. Use learning rate `lr = 0.1`.

Start at `p = 0`:

| Step | p | slope = 2(p−3) | new p = p − 0.1·slope |
|---|---|---|---|
| 1 | 0.00 | −6.00 | 0.00 − 0.1·(−6.00) = 0.60 |
| 2 | 0.60 | −4.80 | 0.60 + 0.48 = 1.08 |
| 3 | 1.08 | −3.84 | 1.08 + 0.384 = 1.46 |
| 4 | 1.46 | −3.07 | 1.46 + 0.307 = 1.77 |
| 5 | 1.77 | −2.45 | 1.77 + 0.245 = 2.02 |

Each step `p` climbs toward `3`, and the slope shrinks as we approach (the ground flattens near the bottom). Keep going and `p → 3`, where the slope is `0` and we stop. **We found the minimum without ever being told where it was** — just by always stepping downhill.

## 7.4 Scaling up to a splat

Now swap the toy for the real thing:
- `p` (one number) → **all** the blob parameters at once: every position, scale, rotation, opacity, and color coefficient. Millions of numbers, each a direction in the landscape.
- `f(p)` → the **loss** from topic 6 (how wrong the render is).

Each training step:
1. **Render** a view from the current blobs (topics 3–5).
2. **Measure** the loss vs. the real photo (topic 6).
3. **Find every slope** — for each of the millions of parameters, which way reduces the loss. This is done by **backpropagation**: because the whole renderer is built from smooth operations (Gaussians, multiplies, sums), the chain rule walks the error backward and produces all slopes in one pass.
4. **Step** every parameter a little downhill.
5. Repeat ~30,000 times.

This is why topic 1 stressed that Gaussians are **smooth**: a smooth renderer has well-defined slopes everywhere, so step 3 works. A renderer with hard edges would have cliffs (undefined slopes) and training would jam.

## 7.5 Why it needs many views

If you only ever minimized the loss for **one** camera, the blobs could cheat — arrange themselves to look right from that single angle and garbage from every other. Because the loss is measured across **all** the captured views (the orbit), the blobs are forced into an arrangement that's correct from every direction at once. That shared constraint is what produces a real 3D object instead of a flat billboard.

---

## Recap

- Gradient descent = repeatedly step **downhill** on the loss landscape.
- Slope (gradient) tells you which way is down and how steep; step `new = old − lr·slope`.
- Worked toy `(p−3)²` converges to `p = 3` purely by stepping downhill.
- For a splat, "p" is millions of blob parameters; slopes come from **backpropagation** through the smooth renderer; ~30k steps.
- Many views keep the blobs honest in 3D.

Next: training also adds and removes blobs as it goes — adaptive density control.
