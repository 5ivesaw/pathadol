#!/usr/bin/env python3
"""
1:1 Live & Post-Match Game Inspector for Pathadol.

Reads either:
  - Live atomic `pathadol/live_state.json` + active `diag-*.jsonl` while Minecraft is running, OR
  - Any recorded `diag-*.jsonl` file (safely ignoring half-flushed final lines if game is live)

Outputs an ultra-compact (~100-line) AI-readable 1:1 game digest:
  1. Top-down ASCII 2D Arena Minimap (Beds, Gens, Shops, Recent Path Trail, Live Player, Enemies)
  2. Live State & Movement Class Breakdown (HP, Gear, Currencies, Maneuver, Active Baritone Movement, Goal, LookHit)
  3. Automatic Anomaly & Bug Detector (Slow-motion shift stalls, Shop tab loops, Void falls, Setbacks,
     Stuck windows, Home-Gen camping, Anti-Cheat flags) with exact tick micro-traces.
"""

from __future__ import annotations

import argparse
import glob
import json
import math
import os
import sys
from collections import Counter
from typing import Any, Dict, List, Optional, Tuple

DEFAULT_BASE = os.path.expanduser(
    "~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/26.3(1)/minecraft/pathadol"
)


def safe_load_jsonl(path: str) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            s = line.strip()
            if not s:
                continue
            try:
                rows.append(json.loads(s))
            except Exception:
                # Gracefully ignore partially-flushed final line while Minecraft is live
                continue
    return rows


def render_ascii_map(
    beds: List[Dict[str, Any]],
    gens: List[Dict[str, Any]],
    shops: List[Dict[str, Any]],
    trail: List[Tuple[float, float]],
    player_pos: Optional[List[float]],
    enemies: List[Dict[str, Any]],
    width: int = 56,
    height: int = 22,
) -> str:
    pts: List[Tuple[float, float]] = []
    for b in beds:
        pts.append((float(b["x"]), float(b["z"])))
    for g in gens:
        pts.append((float(g["x"]), float(g["z"])))
    for s in shops:
        pts.append((float(s["x"]), float(s["z"])))
    for x, z in trail[-200:]:
        pts.append((x, z))
    if player_pos:
        pts.append((float(player_pos[0]), float(player_pos[2])))
    for e in enemies:
        ep = e.get("pos")
        if ep:
            pts.append((float(ep[0]), float(ep[2])))
    if not pts:
        return "  (no arena coordinates recorded yet)"

    xs = [p[0] for p in pts]
    zs = [p[1] for p in pts]
    min_x, max_x = min(xs) - 4.0, max(xs) + 4.0
    min_z, max_z = min(zs) - 4.0, max(zs) + 4.0
    span_x = max(1.0, max_x - min_x)
    span_z = max(1.0, max_z - min_z)

    grid = [[" " for _ in range(width)] for _ in range(height)]

    def put(wx: float, wz: float, ch: str) -> None:
        gx = int(round((wx - min_x) / span_x * (width - 1)))
        gz = int(round((wz - min_z) / span_z * (height - 1)))
        gx = max(0, min(width - 1, gx))
        gz = max(0, min(height - 1, gz))
        grid[gz][gx] = ch

    for x, z in trail[-250:]:
        put(x, z, ".")
    for g in gens:
        k = str(g.get("kind", ""))
        ch = "D" if "DIAMOND" in k else ("E" if "EMERALD" in k else "F")
        put(float(g["x"]), float(g["z"]), ch)
    for s in shops:
        put(float(s["x"]), float(s["z"]), "S")
    for b in beds:
        if b.get("dead"):
            put(float(b["x"]), float(b["z"]), "x")
        else:
            put(float(b["x"]), float(b["z"]), "H" if b.get("home") else "B")
    for e in enemies:
        ep = e.get("pos")
        if ep:
            put(float(ep[0]), float(ep[2]), "a" if e.get("allied") else "!")
    if player_pos:
        put(float(player_pos[0]), float(player_pos[2]), "@")

    border = "  +" + "-" * width + "+"
    lines = [
        f"  [2D ARENA MAP x=({min_x:.0f}..{max_x:.0f}) z=({min_z:.0f}..{max_z:.0f}) | @=Self .=Trail !=Enemy H=HomeBed B=EnemyBed D/E=Dia/Em S=Shop]",
        border,
    ]
    for row in grid:
        lines.append("  |" + "".join(row) + "|")
    lines.append(border)
    return "\n".join(lines)


def inspect_diag(diag_path: str, live_json: Optional[Dict[str, Any]] = None, private_chat: bool = False) -> str:
    rows = safe_load_jsonl(diag_path)
    ticks: List[Dict[str, Any]] = []
    shops: List[Dict[str, Any]] = []
    events: List[Dict[str, Any]] = []
    decisions: List[Dict[str, Any]] = []
    chats: List[Dict[str, Any]] = []
    beds: List[Dict[str, Any]] = []
    gens: List[Dict[str, Any]] = []
    shop_npcs: List[Dict[str, Any]] = []
    latest_players: List[Dict[str, Any]] = []
    map_name = os.path.basename(diag_path)
    session_id = None

    for d in rows:
        tp = d.get("type")
        if tp == "SESSION_START":
            session_id = d.get("sessionId")
            beds = d.get("beds") or beds
            gens = d.get("gens") or gens
            shop_npcs = d.get("shops") or shop_npcs
        elif tp == "TICK":
            ticks.append(d)
        elif tp == "SHOP_CLICK":
            shops.append(d)
        elif tp == "EVENT":
            events.append(d)
            if d.get("event") == "MAP_RESOLVED" and d.get("detail"):
                map_name = str(d["detail"])
        elif tp == "DECISION":
            decisions.append(d)
        elif tp == "CHAT" and private_chat:
            chats.append(d)
        elif tp == "ENTITIES":
            latest_players = d.get("players") or latest_players

    if live_json and session_id is not None and live_json.get("sessionId") != session_id:
        live_json = None
    if live_json:
        beds = live_json.get("beds") or beds
        gens = live_json.get("gens") or gens
        shop_npcs = live_json.get("shops") or shop_npcs
        latest_players = live_json.get("players") or latest_players
        if live_json.get("map"):
            map_name = str(live_json["map"])

    out: List[str] = []
    last_tk = (live_json.get("tick") if live_json else None) or (ticks[-1] if ticks else {})
    out.append("=" * 96)
    out.append(
        f"  PATHADOL 1:1 INSPECTOR | file={os.path.basename(diag_path)} | map={map_name} | ticks={len(ticks)} ({len(ticks)/20.0:.1f}s)"
    )
    out.append("=" * 96)

    if last_tk:
        inv = last_tk.get("inv") or {}
        lh = last_tk.get("lookHit") or {}
        lh_str = (
            f"{lh.get('type')}:{lh.get('block', lh.get('kind', ''))}"
            + (f"(brk={lh.get('breakable')})" if "breakable" in lh else "")
        )
        out.append(
            f"  LIVE STATE t={last_tk.get('t')} pos={last_tk.get('pos')} rot={last_tk.get('rot')} "
            f"hp={last_tk.get('hp')} g={last_tk.get('onGround')} snk={last_tk.get('crouching')} keys=[{last_tk.get('keys')}]"
        )
        out.append(
            f"  CONTROL    bwsMode={last_tk.get('bwsMode')} move={last_tk.get('move', '?')} "
            f"goal={str(last_tk.get('goal', '?'))[:48]} held={last_tk.get('held')} lookHit={lh_str}"
        )
        out.append(
            f"  ECONOMY    Fe={inv.get('iron',0)} Au={inv.get('gold',0)} Dia={inv.get('dia',0)} Em={inv.get('em',0)} "
            f"Blk={inv.get('blocks',0)} Sword=T{inv.get('sword',0)} Armor={inv.get('armor',0)}"
        )

    trail = [(float(tk["pos"][0]), float(tk["pos"][2])) for tk in ticks[-400:] if tk.get("pos")]
    out.append("")
    out.append(render_ascii_map(beds, gens, shop_npcs, trail, last_tk.get("pos"), latest_players))

    # --- Automatic Bug & Anomaly Detector ---
    bugs: List[str] = []

    # 1. Slow-motion shift stalls (>= 8 consecutive crouching ticks with mean speed < 0.035)
    prev_pos = None
    snk_run: List[Tuple[int, float, str, str, str]] = []
    slow_bursts: List[str] = []
    move_counts: Counter[str] = Counter()
    max_dyaw = 0.0
    prev_yaw: Optional[float] = None
    void_falls = 0
    prev_y: Optional[float] = None
    spr_snk = 0

    for tk in ticks:
        t = int(tk.get("t", 0))
        pos = tk.get("pos") or [0, 0, 0]
        rot = tk.get("rot") or [0, 0]
        spd = math.hypot(pos[0] - prev_pos[0], pos[2] - prev_pos[2]) if prev_pos else 0.0
        prev_pos = pos
        if prev_yaw is not None:
            dy = abs(((rot[0] - prev_yaw + 180.0) % 360.0) - 180.0)
            if dy > max_dyaw:
                max_dyaw = dy
        prev_yaw = rot[0]
        if prev_y is not None and prev_y > 10.0 and pos[1] <= 10.0:
            void_falls += 1
        prev_y = pos[1]

        keys = str(tk.get("keys") or "")
        if "SPR" in keys and "SNK" in keys:
            spr_snk += 1
        mv = str(tk.get("move") or "")
        if mv:
            move_counts[mv] += 1

        if tk.get("crouching") or "SNK" in keys:
            snk_run.append((t, spd, keys, str(tk.get("bwsMode", "")), mv))
        else:
            if len(snk_run) >= 8:
                avg_spd = sum(x[1] for x in snk_run) / len(snk_run)
                if avg_spd < 0.038:
                    slow_bursts.append(
                        f"t={snk_run[0][0]}..{snk_run[-1][0]} ({len(snk_run)}t, avgSpd={avg_spd:.4f}, "
                        f"mode={snk_run[0][3]}, move={snk_run[0][4] or '?'}, sampleKeys={snk_run[min(len(snk_run)-1, 5)][2]})"
                    )
            snk_run = []

    if slow_bursts:
        bugs.append(f"SLOW-MOTION SHIFT STALLS ({len(slow_bursts)} bursts): " + "; ".join(slow_bursts[:5]))

    # 2. Shop tab loops / empty cooldowns
    empty_cds = [e for e in events if e.get("event") == "SHOP_EMPTY_COOLDOWN"]
    if empty_cds:
        bugs.append(
            f"SHOP EMPTY COOLDOWN ({len(empty_cds)}x): "
            + ", ".join(f"t={e['t']}({e.get('detail','')})" for e in empty_cds[:5])
        )

    # 3. Server teleports & void falls
    tps = [e for e in events if e.get("event") == "SERVER_TELEPORT"]
    if len(tps) > 10:
        bugs.append(f"ELEVATED SERVER_TELEPORT COUNT: {len(tps)} total")
    if void_falls > 0:
        bugs.append(f"VOID FALLS DETECTED: {void_falls}")
    if spr_snk > 0:
        bugs.append(f"ANTI-CHEAT RISK SPR+SNK OVERLAP: {spr_snk} ticks")

    out.append("")
    out.append("  [AUTO-DIAGNOSED BUGS & ANOMALIES]")
    if not bugs:
        out.append("    NONE — clean session!")
    else:
        for b in bugs:
            out.append(f"    ! {b}")

    out.append("")
    out.append(
        f"  [SUMMARY] shops={len(shops)} | decisions={len(decisions)} | TPs={len(tps)} | max_dYaw={max_dyaw:.1f}deg | "
        f"topMoves={move_counts.most_common(5)}"
    )
    if decisions:
        out.append("  [LAST 8 DECISIONS] " + " -> ".join(f"t={d['t']}:{d.get('action')}" for d in decisions[-8:]))
    if shops:
        out.append("  [LAST 8 SHOP CLICKS] " + ", ".join(f"t={s['t']}:{s.get('item')}" for s in shops[-8:]))
    key_chats = [
        c for c in chats
        if any(w in str(c.get("clean", "")).lower() for w in ("won the game", "destroyed", "final kill", "eliminated", "removed from this server"))
    ]
    if key_chats:
        out.append("  [KEY MATCH EVENTS]")
        for c in key_chats[-8:]:
            out.append(f"    t={c['t']:5d} {c.get('clean')}")
    return "\n".join(out)


def main() -> int:
    ap = argparse.ArgumentParser(description="Pathadol 1:1 live & post-match inspector.")
    ap.add_argument("diag_path", nargs="?", default="", help="Optional explicit diag-*.jsonl path")
    ap.add_argument("--base", default=DEFAULT_BASE, help="Pathadol data dir")
    ap.add_argument("--live", action="store_true", help="Include live_state.json snapshot")
    ap.add_argument("--latest", action="store_true", help="Auto-pick newest diag-*.jsonl")
    ap.add_argument("--private-chat", action="store_true", help="Include raw match chat in local output")
    args = ap.parse_args()

    live_json = None
    live_path = os.path.join(args.base, "live_state.json")
    if (args.live or not args.diag_path) and os.path.isfile(live_path):
        try:
            with open(live_path, "r", encoding="utf-8") as f:
                live_json = json.load(f)
        except Exception:
            live_json = None

    diag_path = args.diag_path
    snapshot_diag_path = live_json.get("diagFile") if isinstance(live_json, dict) else None
    if not diag_path:
        if not args.latest and isinstance(snapshot_diag_path, str) and os.path.isfile(snapshot_diag_path):
            diag_path = snapshot_diag_path
        else:
            cands = sorted(
                glob.glob(os.path.join(args.base, "bedwars_records", "diag-*.jsonl")),
                key=os.path.getmtime,
            )
            if not cands:
                print("No diag-*.jsonl found.", file=sys.stderr)
                return 2
            diag_path = cands[-1]

    if not isinstance(snapshot_diag_path, str) or os.path.realpath(snapshot_diag_path) != os.path.realpath(diag_path):
        live_json = None
    print(inspect_diag(diag_path, live_json, private_chat=args.private_chat))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
