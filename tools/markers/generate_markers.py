#!/usr/bin/env python3
"""
generate_markers.py — print-ready ArUco markers for the drone capture rig.

Generates IDs 0-7 from DICT_4X4_50 (what the app decodes), each at a chosen
physical size, with a role label (INNER 0-3 / OUTER 4-7) printed underneath.
Outputs individual PNGs + a single multi-page PDF, one marker per page,
sized so it prints at exactly the requested mm at 100% scale.

Usage:
    pip install opencv-contrib-python pillow
    python generate_markers.py                 # 8 markers, 150 mm
    python generate_markers.py --size-mm 200 --count 8

Print the PDF at 100% / "actual size" (no fit-to-page). Verify one marker
with a ruler — the black square should measure the requested size.
"""

import argparse
import os

import cv2
import numpy as np
from PIL import Image

DICT = cv2.aruco.DICT_4X4_50
DPI = 300                      # print resolution
MM_PER_INCH = 25.4


def role_for(marker_id: int) -> str:
    if 0 <= marker_id <= 3:
        return "INNER"
    if 4 <= marker_id <= 7:
        return "OUTER"
    return "—"


def mm_to_px(mm: float) -> int:
    return int(round(mm / MM_PER_INCH * DPI))


def make_marker(marker_id: int, size_mm: float, quiet_mm: float = 15.0, label_mm: float = 12.0):
    """Return a PIL image: marker + white quiet border + role/ID label."""
    aruco = cv2.aruco.getPredefinedDictionary(DICT)
    side_px = mm_to_px(size_mm)
    quiet_px = mm_to_px(quiet_mm)
    label_px = mm_to_px(label_mm)

    marker = cv2.aruco.generateImageMarker(aruco, marker_id, side_px)  # grayscale, 0/255
    canvas_w = side_px + 2 * quiet_px
    canvas_h = side_px + 2 * quiet_px + label_px
    canvas = np.full((canvas_h, canvas_w), 255, dtype=np.uint8)
    canvas[quiet_px:quiet_px + side_px, quiet_px:quiet_px + side_px] = marker

    img = Image.fromarray(canvas).convert("RGB")
    # label text
    from PIL import ImageDraw, ImageFont
    draw = ImageDraw.Draw(img)
    text = f"ID {marker_id}  ({role_for(marker_id)})  4x4_50  {size_mm:.0f}mm"
    try:
        font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", int(label_px * 0.5))
    except Exception:
        font = ImageFont.load_default()
    tw = draw.textlength(text, font=font)
    draw.text(((canvas_w - tw) / 2, side_px + 2 * quiet_px + label_px * 0.1),
              text, fill=(0, 0, 0), font=font)
    return img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=8, help="markers (IDs 0..count-1)")
    ap.add_argument("--size-mm", type=float, default=150.0)
    ap.add_argument("--out-dir", default=os.path.dirname(__file__))
    args = ap.parse_args()

    out_dir = os.path.abspath(args.out_dir)
    png_dir = os.path.join(out_dir, "png")
    os.makedirs(png_dir, exist_ok=True)

    pages = []
    for mid in range(args.count):
        img = make_marker(mid, args.size_mm)
        p = os.path.join(png_dir, f"marker_{mid}_{role_for(mid).lower()}.png")
        img.save(p, dpi=(DPI, DPI))
        pages.append(img)
        print(f"  {p}  ({role_for(mid)})")

    # Save PDF as 1-bit (pure B/W) so Pillow uses CCITT, not JPEG (avoids
    # needing libjpeg). Markers + labels are crisp black/white, so no loss.
    pdf_path = os.path.join(out_dir, "markers.pdf")
    bw = [p.convert("1") for p in pages]
    bw[0].save(pdf_path, save_all=True, append_images=bw[1:], resolution=DPI)
    print(f"\nwrote {pdf_path}  ({args.count} markers @ {args.size_mm:.0f} mm)")
    print("Print at 100% / actual size. Inner = 0-3, Outer = 4-7.")


if __name__ == "__main__":
    main()
