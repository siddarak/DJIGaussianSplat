#!/usr/bin/env python3
"""
extract_dims.py — compute an object's bounding-box dimensions from a CAD file
and write an ObjectConfig JSON the drone app can consume.

Output is always in METERS and normalized to Z-up (height = up-axis extent).

Mesh formats (STL/OBJ/PLY/GLB/3MF...) use trimesh.
B-rep formats (STEP/IGES) use cadquery if installed.

Usage:
    python extract_dims.py teapot.stl --id teapot-v1 --units mm
    python extract_dims.py part.step  --id chair-v2   --units mm --up Z

Notes:
  - STL/OBJ are unit-less. If --units is omitted, units are written as
    "UNKNOWN" and the app will prompt + confirm before any flight.
  - --up is the file's up-axis (Z or Y). Output is normalized so height is
    always the Z extent.
"""

import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone

UNIT_TO_M = {"mm": 0.001, "cm": 0.01, "m": 1.0, "in": 0.0254}
MESH_EXT = {".stl", ".obj", ".ply", ".glb", ".gltf", ".3mf", ".off", ".dae", ".fbx"}
BREP_EXT = {".step", ".stp", ".iges", ".igs"}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def extents_mesh(path):
    """Return (dx, dy, dz) and (min[3], max[3]) in the file's own units via trimesh."""
    import trimesh
    m = trimesh.load(path, force="mesh")
    if m.is_empty:
        raise ValueError("mesh is empty / failed to load")
    lo = m.bounds[0].tolist()
    hi = m.bounds[1].tolist()
    ext = m.extents.tolist()
    return ext, lo, hi


def extents_brep(path):
    """Return (dx, dy, dz) and (min[3], max[3]) via cadquery (OpenCASCADE)."""
    import cadquery as cq
    ext_lower = path.lower()
    shape = cq.importers.importStep(path) if ext_lower.endswith((".step", ".stp")) \
        else cq.importers.importShape("IGES", path)
    bb = shape.val().BoundingBox()
    ext = [bb.xlen, bb.ylen, bb.zlen]
    lo = [bb.xmin, bb.ymin, bb.zmin]
    hi = [bb.xmax, bb.ymax, bb.zmax]
    return ext, lo, hi


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cad_file")
    ap.add_argument("--id", required=True, help="object id, kebab-case + version, e.g. teapot-v1")
    ap.add_argument("--units", choices=list(UNIT_TO_M.keys()), default=None,
                    help="source file units; omit only if truly unknown (-> UNKNOWN)")
    ap.add_argument("--up", choices=["Z", "Y"], default="Z", help="file up-axis (default Z)")
    ap.add_argument("--out-dir", default=os.path.join(os.path.dirname(__file__), "..", "..", "objects"))
    args = ap.parse_args()

    path = args.cad_file
    if not os.path.isfile(path):
        sys.exit(f"file not found: {path}")
    ext_lower = os.path.splitext(path)[1].lower()

    # --- get raw extents in file units ---
    if ext_lower in MESH_EXT:
        ext, lo, hi = extents_mesh(path)
    elif ext_lower in BREP_EXT:
        ext, lo, hi = extents_brep(path)
    else:
        sys.exit(f"unsupported format: {ext_lower}")

    # --- unit scale (UNKNOWN -> factor 1.0, flagged for app to confirm) ---
    units = args.units if args.units else "UNKNOWN"
    factor = UNIT_TO_M.get(units, 1.0)

    dx, dy, dz = (v * factor for v in ext)
    lo_m = [v * factor for v in lo]
    hi_m = [v * factor for v in hi]

    # --- normalize to Z-up: height = up extent, other two = footprint ---
    if args.up == "Z":
        height, w0, w1 = dz, dx, dy
    else:  # Y-up file: height is Y extent; swap into Z-up output
        height, w0, w1 = dy, dx, dz
        lo_m = [lo_m[0], lo_m[2], lo_m[1]]
        hi_m = [hi_m[0], hi_m[2], hi_m[1]]

    config = {
        "schema_version": 1,
        "object_id": args.id,
        "source_file": os.path.basename(path),
        "source_sha256": sha256(path),
        "units_original": units,
        "dimensions_m": {"height": round(height, 4), "width": round(w0, 4), "depth": round(w1, 4)},
        "bbox_min_m": [round(v, 4) for v in lo_m],
        "bbox_max_m": [round(v, 4) for v in hi_m],
        "up_axis": "Z",
        "generated_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "generator": "cad_dims v1",
    }

    out_dir = os.path.abspath(args.out_dir)
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, f"{args.id}.json")
    with open(out_path, "w") as f:
        json.dump(config, f, indent=2)

    # --- upsert into index.json ---
    index_path = os.path.join(out_dir, "index.json")
    index = {"objects": []}
    if os.path.isfile(index_path):
        with open(index_path) as f:
            index = json.load(f)
    index["objects"] = [o for o in index.get("objects", []) if o.get("id") != args.id]
    index["objects"].append({"id": args.id, "file": f"{args.id}.json", "height_m": round(height, 4)})
    with open(index_path, "w") as f:
        json.dump(index, f, indent=2)

    print(f"wrote {out_path}")
    print(f"  height {height:.3f} m  footprint {w0:.3f} x {w1:.3f} m  units={units}")
    if units == "UNKNOWN":
        print("  WARNING: units UNKNOWN — app will prompt + confirm before flight")


if __name__ == "__main__":
    main()
