# marker generator

Print-ready ArUco markers that match what the app decodes (`DICT_4X4_50`).

## Install + run

```bash
pip install opencv-contrib-python pillow
python generate_markers.py              # IDs 0-7, 150 mm
python generate_markers.py --size-mm 200
```

Outputs:
- `png/marker_<id>_<role>.png` — individual markers
- `markers.pdf` — one marker per page, print-ready

## Print

- Print `markers.pdf` at **100% / actual size** (NOT fit-to-page).
- Verify with a ruler: the black square = the requested mm.
- Mount flat on rigid backing.

## Layout

- **IDs 0–3 = INNER** perimeter (close ring around the object)
- **IDs 4–7 = OUTER** perimeter (far ring)

This matches the app's default role convention. To use different IDs/positions,
edit `site/default.json` (see `site/default.example.json`).
