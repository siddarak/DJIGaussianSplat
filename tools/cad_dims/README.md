# cad_dims — object dimension extractor

Runs **locally** (laptop), not on the drone. Reads a CAD file, computes the
object's bounding-box dimensions, writes an `ObjectConfig` JSON the app reads.

Output is always **meters**, normalized **Z-up** (height = up-axis extent).

## Install

```bash
pip install -r requirements.txt
# STEP/IGES also need: pip install cadquery
```

## Use

```bash
# mesh (STL/OBJ/PLY/GLB) — give units, they are not stored in the file
python extract_dims.py teapot.stl --id teapot-v1 --units mm

# B-rep (STEP/IGES)
python extract_dims.py part.step --id chair-v2 --units mm
```

Writes `objects/teapot-v1.json` and updates `objects/index.json`.

## Rules

- **Units mandatory for meshes.** STL/OBJ are unit-less. Omit `--units` only if
  truly unknown → written as `"UNKNOWN"` → app prompts + confirms before flight.
- **Model upright.** Facing direction doesn't matter (full orbit). Up-axis defaults
  to Z; pass `--up Y` for Y-up files (glTF) and output is normalized to Z-up.
- `source_sha256` lets the app detect a stale config if the CAD changed.

## Output schema (v1)

```json
{
  "schema_version": 1,
  "object_id": "teapot-v1",
  "source_file": "teapot.stl",
  "source_sha256": "…",
  "units_original": "mm",
  "dimensions_m": { "height": 0.182, "width": 0.240, "depth": 0.155 },
  "bbox_min_m": [-0.12, -0.077, 0.0],
  "bbox_max_m": [0.12, 0.078, 0.182],
  "up_axis": "Z",
  "generated_utc": "2026-06-04T00:00:00Z",
  "generator": "cad_dims v1"
}
```

Copy the generated `objects/` files to the device at
`Android/data/com.example.drones/files/objects/` (the app reads from there).
