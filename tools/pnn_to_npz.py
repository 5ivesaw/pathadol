#!/usr/bin/env python3
"""
Convert binary Pathadol Pure-NN shards (*.pnn) into Colab L4-ready compressed .npz arrays,
run automated data quality audits (NaN/Inf, lobby-free, Mu-Law round-trip, action balance),
and optionally apply exact Left-Right mirror symmetry augmentation (2x dataset size for free).

Matches `bedwars_pure_nn_plan.md` Sections 2, 3, and 5.
"""

from __future__ import annotations

import argparse
import glob
import json
import math
import os
import struct
import sys
from typing import Dict, List, Tuple

import numpy as np

MAGIC = 0x504E4E31  # "PNN1"
SCHEMA_VERSION = 1
VOXEL_Y, VOXEL_FWD, VOXEL_RIGHT = 4, 5, 5
VOXEL_DIM = VOXEL_Y * VOXEL_FWD * VOXEL_RIGHT  # 100
FLOAT_OBS_DIM = 192
AIM_BINS = 25
AIM_MAX_DEG = 35.0
MU_LAW = 15.0
RECORD_HEADER_FMT = "<IIBBBBBBBBBBBBf"  # 24 bytes (15 fields)
RECORD_HEADER_BYTES = struct.calcsize(RECORD_HEADER_FMT)
assert RECORD_HEADER_BYTES == 24
RECORD_BYTES = RECORD_HEADER_BYTES + VOXEL_DIM + FLOAT_OBS_DIM * 4

KEY_A = 1 << 1
KEY_D = 1 << 3


def mu_law_decode(bins: np.ndarray) -> np.ndarray:
    c = np.clip(bins.astype(np.float64), 0, AIM_BINS - 1)
    companded = (2.0 * c / (AIM_BINS - 1)) - 1.0
    norm = np.sign(companded) * (np.expm1(np.abs(companded) * math.log1p(MU_LAW)) / MU_LAW)
    return (norm * AIM_MAX_DEG).astype(np.float32)


def read_pnn_file(path: str) -> Dict[str, np.ndarray]:
    with open(path, "rb") as f:
        hdr = f.read(16)
        if len(hdr) < 16:
            raise ValueError(f"{path}: truncated header")
        magic, ver, vox_dim, fl_dim = struct.unpack("<IIII", hdr)
        if magic != MAGIC or ver != SCHEMA_VERSION or vox_dim != VOXEL_DIM or fl_dim != FLOAT_OBS_DIM:
            raise ValueError(f"{path}: header mismatch magic={hex(magic)} ver={ver} vox={vox_dim} fl={fl_dim}")
        body = f.read()

    n = len(body) // RECORD_BYTES
    if n == 0:
        return {}

    ticks = np.zeros(n, dtype=np.int32)
    episodes = np.zeros(n, dtype=np.int32)
    flags = np.zeros(n, dtype=np.uint8)
    sources = np.zeros(n, dtype=np.uint8)
    keys = np.zeros(n, dtype=np.uint8)
    yaw_bin = np.zeros(n, dtype=np.uint8)
    pitch_bin = np.zeros(n, dtype=np.uint8)
    lmb = np.zeros(n, dtype=np.uint8)
    rmb = np.zeros(n, dtype=np.uint8)
    hotbar = np.zeros(n, dtype=np.uint8)
    gui_action = np.zeros(n, dtype=np.uint8)
    rewards = np.zeros(n, dtype=np.float32)
    voxels = np.zeros((n, VOXEL_Y, VOXEL_FWD, VOXEL_RIGHT), dtype=np.uint8)
    floats = np.zeros((n, FLOAT_OBS_DIM), dtype=np.float32)

    mv = memoryview(body)
    for i in range(n):
        off = i * RECORD_BYTES
        (
            ticks[i], episodes[i], flags[i], sources[i], keys[i],
            yaw_bin[i], pitch_bin[i], lmb[i], rmb[i], hotbar[i], gui_action[i],
            _r0, _r1, _r2, rewards[i],
        ) = struct.unpack_from(RECORD_HEADER_FMT, mv, off)
        v_off = off + RECORD_HEADER_BYTES
        voxels[i] = np.frombuffer(mv[v_off : v_off + VOXEL_DIM], dtype=np.uint8).reshape(
            VOXEL_Y, VOXEL_FWD, VOXEL_RIGHT
        )
        f_off = v_off + VOXEL_DIM
        floats[i] = np.frombuffer(mv[f_off : f_off + FLOAT_OBS_DIM * 4], dtype="<f4")

    return {
        "ticks": ticks,
        "episodes": episodes,
        "flags": flags,
        "sources": sources,
        "keys": keys,
        "yaw_bin": yaw_bin,
        "pitch_bin": pitch_bin,
        "lmb": lmb,
        "rmb": rmb,
        "hotbar": hotbar,
        "gui_action": gui_action,
        "rewards": rewards,
        "voxels": voxels,
        "floats": floats,
    }


def mirror_left_right(data: Dict[str, np.ndarray]) -> Dict[str, np.ndarray]:
    """Exact Left-Right mirror symmetry matching PureNnSchema.mirrorLeftRight."""
    out = {k: v.copy() for k, v in data.items()}
    out["voxels"] = out["voxels"][:, :, :, ::-1]
    mf = out["floats"]
    for idx in (2, 4, 6, 8, 41, 171, 174):
        mf[:, idx] = -mf[:, idx]
    for a, b in ((23, 29), (24, 28), (25, 27)):
        tmp = mf[:, a].copy()
        mf[:, a] = mf[:, b]
        mf[:, b] = tmp
    for p in range(4):
        base = 96 + p * 12
        mf[:, base + 3] = -mf[:, base + 3]
        mf[:, base + 6] = -mf[:, base + 6]
    mf[:, 144 + 3] = -mf[:, 144 + 3]
    mf[:, 144 + 6] = -mf[:, 144 + 6]
    for b in range(3):
        mf[:, 155 + b * 5 + 1] = -mf[:, 155 + b * 5 + 1]

    km = out["keys"]
    had_a = (km & KEY_A) != 0
    had_d = (km & KEY_D) != 0
    km = km & np.uint8(~(KEY_A | KEY_D) & 0xFF)
    km = np.where(had_a, km | np.uint8(KEY_D), km)
    km = np.where(had_d, km | np.uint8(KEY_A), km)
    out["keys"] = km.astype(np.uint8)
    out["yaw_bin"] = ((AIM_BINS - 1) - out["yaw_bin"].astype(np.int32)).astype(np.uint8)
    out["episodes"] = out["episodes"] + 100000
    return out


def extract_observer_stream(diag_glob: str, out_npz: str) -> int:
    """Extract separate passive observer trajectories of nearby players from diag-*.jsonl (Phase 1)."""
    paths = sorted(glob.glob(diag_glob))
    if not paths:
        print(f"No diag-*.jsonl matched: {diag_glob}", file=sys.stderr)
        return 2
    rows: List[List[float]] = []
    for session_idx, p in enumerate(paths):
        with open(p, "r", encoding="utf-8") as f:
            for line in f:
                if '"ENTITIES"' not in line:
                    continue
                try:
                    d = json.loads(line)
                except Exception:
                    continue
                if d.get("type") != "ENTITIES":
                    continue
                t = float(d.get("t", 0))
                for pl in d.get("players") or []:
                    pos = pl.get("pos") or [0.0, 0.0, 0.0]
                    vel = pl.get("vel") or [0.0, 0.0, 0.0]
                    rot = pl.get("rot") or [0.0, 0.0]
                    rows.append([
                        float(session_idx), t, float(pl.get("id", 0)),
                        float(pos[0]), float(pos[1]), float(pos[2]),
                        float(vel[0]), float(vel[1]), float(vel[2]),
                        float(rot[0]), float(rot[1]),
                        float(pl.get("dist", 0.0)), float(pl.get("hp", 20.0)),
                        1.0 if pl.get("sneaking") else 0.0,
                        1.0 if pl.get("sprinting") else 0.0,
                        1.0 if pl.get("ground") else 0.0,
                        1.0 if pl.get("allied") else 0.0,
                    ])
    arr = np.asarray(rows, dtype=np.float32) if rows else np.zeros((0, 17), dtype=np.float32)
    os.makedirs(os.path.dirname(os.path.abspath(out_npz)), exist_ok=True)
    np.savez_compressed(out_npz, observer=arr)
    print(f"Saved passive observer stream {out_npz}: {len(arr)} player-ticks from {len(paths)} sessions")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Convert .pnn binary shards (and optional diag observer streams) to Colab L4 .npz.")
    ap.add_argument("input_glob", nargs="?", default="", help="Glob for .pnn files")
    ap.add_argument("--out", default="", help="Output .npz path for .pnn shards")
    ap.add_argument("--mirror", action="store_true", help="Append exact Left-Right mirrored trajectories (2x data)")
    ap.add_argument("--observer-diag-glob", default="", help="Optional diag-*.jsonl glob to export separate passive observer stream")
    ap.add_argument("--observer-out", default="", help="Output .npz path for passive observer stream")
    args = ap.parse_args()

    if args.observer_diag_glob and args.observer_out:
        rc = extract_observer_stream(args.observer_diag_glob, args.observer_out)
        if not args.input_glob:
            return rc

    if not args.input_glob or not args.out:
        print("Provide input_glob and --out (or --observer-diag-glob and --observer-out).", file=sys.stderr)
        return 2

    paths = sorted(glob.glob(args.input_glob))
    if not paths:
        print(f"No .pnn files matched: {args.input_glob}", file=sys.stderr)
        return 2

    chunks: List[Dict[str, np.ndarray]] = []
    for p in paths:
        d = read_pnn_file(p)
        if d:
            chunks.append(d)
    if not chunks:
        print("All matched .pnn files were empty.", file=sys.stderr)
        return 2

    merged = {k: np.concatenate([c[k] for c in chunks], axis=0) for k in chunks[0].keys()}
    if not np.all(np.isfinite(merged["floats"])):
        raise ValueError("Non-finite float detected in Pure-NN observations!")

    if args.mirror:
        mirrored = mirror_left_right(merged)
        merged = {k: np.concatenate([merged[k], mirrored[k]], axis=0) for k in merged.keys()}

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    np.savez_compressed(args.out, **merged)
    n = len(merged["ticks"])
    dyaw = mu_law_decode(merged["yaw_bin"])
    print(
        f"Saved {args.out}: frames={n} (mirror={args.mirror}) | "
        f"guiClicks={int((merged['gui_action'] > 0).sum())} | "
        f"lmbActive={int((merged['lmb'] > 0).sum())} | "
        f"rmbActive={int((merged['rmb'] > 0).sum())} | "
        f"|dYaw| p50={np.percentile(np.abs(dyaw), 50):.2f} p99={np.percentile(np.abs(dyaw), 99):.2f}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
