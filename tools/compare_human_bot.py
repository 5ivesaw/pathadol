#!/usr/bin/env python3
"""
Side-by-side blackbox comparison tool for human (#bws rec human) vs bot BedWars sessions.

Reads diag-*.jsonl (and sibling dataset-*.jsonl when present) and outputs:
  - Session metadata, economy, shop purchases & upgrade milestones
  - Bed defense, movement/sneak speed percentiles & bridge cadence
  - Combat CPS / dt==1 bursts, LMB+RMB overlap, crosshair accuracy
  - Anti-cheat / vanilla protocol audit (SERVER_TELEPORT, ultra-slow sneak, SPR+SNK, clutch spam)
  - Side-by-side timeline of key purchases, upgrades, combat & anomalies

Usage:
  python3 tools/compare_human_bot.py <human_or_a_diag.jsonl> [<bot_or_b_diag.jsonl>]
"""

from __future__ import annotations

import argparse
import collections
import json
import math
import os
import re
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


KEY_RE = re.compile(r"([a-zA-Z_]+)=([^,\s]+)")


def parse_detail_kv(detail: str) -> Dict[str, str]:
    out: Dict[str, str] = {}
    if not detail:
        return out
    for k, v in KEY_RE.findall(detail):
        out[k] = v
    return out


def percentile(sorted_vals: List[float], p: float) -> Optional[float]:
    if not sorted_vals:
        return None
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    idx = (len(sorted_vals) - 1) * p
    lo = int(math.floor(idx))
    hi = min(len(sorted_vals) - 1, lo + 1)
    frac = idx - lo
    return sorted_vals[lo] * (1.0 - frac) + sorted_vals[hi] * frac


def fmt_val(v: Optional[float], spec: str = ".4f", default: str = "-") -> str:
    if v is None:
        return default
    return format(v, spec)


def infer_sibling_dataset(diag_path: Path) -> Optional[Path]:
    name = diag_path.name
    if name.startswith("diag-"):
        cand = diag_path.with_name("dataset-" + name[len("diag-"):])
        if cand.is_file():
            return cand
    return None


def analyze_session(diag_path: Path) -> Dict[str, Any]:
    meta: Dict[str, Any] = {
        "file": diag_path.name,
        "map": "unknown",
        "source": "unknown",
        "ruleset": "UNKNOWN",
        "homeTeam": "UNKNOWN",
        "ticks": 0,
        "durationSec": 0.0,
    }
    stem = diag_path.stem
    parts = stem.split("-")
    if len(parts) >= 4:
        meta["map"] = "-".join(parts[3:])

    ticks: Dict[int, Dict[str, Any]] = {}
    shop_clicks: List[Dict[str, Any]] = []
    attack_fires: List[Dict[str, Any]] = []
    block_places: List[Dict[str, Any]] = []
    autoclutches: List[Dict[str, Any]] = []
    teleports: List[Dict[str, Any]] = []
    events_timeline: List[Tuple[int, str]] = []
    latest_match_state: Dict[str, Any] = {}

    first_ms: Optional[int] = None
    last_ms: Optional[int] = None

    with diag_path.open("r", encoding="utf-8", errors="replace") as fh:
        for raw_line in fh:
            raw_line = raw_line.strip()
            if not raw_line:
                continue
            try:
                rec = json.loads(raw_line)
            except json.JSONDecodeError:
                continue

            rtype = rec.get("type")
            t = int(rec.get("t", 0) or 0)
            ms = rec.get("ms")
            if isinstance(ms, (int, float)):
                ms_int = int(ms)
                if first_ms is None:
                    first_ms = ms_int
                last_ms = ms_int

            if rtype == "SESSION_START":
                if rec.get("source"):
                    meta["source"] = str(rec["source"])
                if rec.get("ruleset"):
                    meta["ruleset"] = str(rec["ruleset"])
                if rec.get("homeTeam"):
                    meta["homeTeam"] = str(rec["homeTeam"])
            elif rtype == "TICK":
                ticks[t] = rec
                meta["ticks"] = max(meta["ticks"], t)
            elif rtype == "SHOP_CLICK":
                shop_clicks.append(rec)
                item = rec.get("item") or rec.get("buyKey") or f"slot_{rec.get('slot')}"
                cost = rec.get("cost") or ""
                events_timeline.append((t, f"SHOP {item} ({cost}) [Fe={rec.get('iron',0)} Au={rec.get('gold',0)} Dia={rec.get('dia',0)} Em={rec.get('em',0)}]"))
            elif rtype == "MATCH_STATE":
                latest_match_state = rec
            elif rtype == "EVENT":
                ev = rec.get("event", "")
                detail = str(rec.get("detail", "") or "")
                kv = parse_detail_kv(detail)
                if ev == "MAP_RESOLVED" and detail:
                    meta["map"] = detail
                elif ev == "ATTACK_FIRE":
                    attack_fires.append({"t": t, "ms": ms, "detail": detail, "kv": kv})
                elif ev == "BLOCK_PLACE":
                    block_places.append({"t": t, "detail": detail, "kv": kv})
                elif ev == "AUTOCLUTCH":
                    autoclutches.append({"t": t, "detail": detail, "kv": kv})
                    events_timeline.append((t, f"AUTOCLUTCH {detail}"))
                elif ev == "SERVER_TELEPORT":
                    teleports.append({"t": t, "detail": detail})
                    if t > 20:
                        events_timeline.append((t, f"TELEPORT {detail}"))

    if first_ms is not None and last_ms is not None and last_ms > first_ms:
        meta["durationSec"] = (last_ms - first_ms) / 1000.0
    else:
        meta["durationSec"] = meta["ticks"] / 20.0

    dataset_path = infer_sibling_dataset(diag_path)
    maneuver_counts: collections.Counter[str] = collections.Counter()
    dataset_rows = 0
    teacher_matches = 0
    if dataset_path is not None:
        with dataset_path.open("r", encoding="utf-8", errors="replace") as dfh:
            for raw_line in dfh:
                raw_line = raw_line.strip()
                if not raw_line:
                    continue
                try:
                    drec = json.loads(raw_line)
                except json.JSONDecodeError:
                    continue
                dataset_rows += 1
                if meta["source"] == "unknown" and drec.get("source"):
                    meta["source"] = str(drec["source"])
                aname = str(drec.get("actionName", "UNKNOWN"))
                maneuver_counts[aname] += 1
                if drec.get("action") == drec.get("teacherHint"):
                    teacher_matches += 1

    # Kinematics, sneak speed, SPR+SNK, damage/deaths, milestones
    sorted_t = sorted(ticks.keys())
    sneak_speeds: List[float] = []
    ultra_slow_sneak_ticks = 0
    spr_snk_key_ticks = 0
    spr_snk_entity_ticks = 0
    deaths = 0
    void_falls = 0
    hits_taken = 0
    prev_hp: Optional[float] = None
    prev_hurt = 0
    prev_sword = 1
    prev_armor = 7.0
    prev_blocks = 0
    first_block_drop_t: Optional[int] = None
    first_sword_upgrade_t: Optional[int] = None
    first_armor_upgrade_t: Optional[int] = None
    max_sword = 1
    max_armor = 0.0
    rmb_clicks_while_sneak = 0
    rmb_total = 0
    prev_rmb = False
    rmb_snk_intervals: List[int] = []
    last_rmb_snk_t: Optional[int] = None
    max_bws_mode_counts: collections.Counter[str] = collections.Counter()

    for t in sorted_t:
        tk = ticks[t]
        vel = tk.get("vel") or [0.0, 0.0, 0.0]
        vx, vy, vz = float(vel[0]), float(vel[1]), float(vel[2])
        hspeed = math.hypot(vx, vz)
        on_ground = bool(tk.get("onGround", False))
        sprinting = bool(tk.get("sprinting", False))
        crouching = bool(tk.get("crouching", False))
        keys_str = str(tk.get("keys", "") or "")
        key_tokens = {k.strip() for k in keys_str.split(",") if k.strip()}
        move_tokens = any(m in keys_str for m in ("W", "A", "S", "D"))
        snk_key = "SNK" in key_tokens
        spr_key = "SPR" in key_tokens
        rmb_key = "RMB" in key_tokens

        bws_mode = str(tk.get("bwsMode", "IDLE"))
        max_bws_mode_counts[bws_mode] += 1

        if spr_key and snk_key:
            spr_snk_key_ticks += 1
        if sprinting and crouching:
            spr_snk_entity_ticks += 1

        if on_ground and crouching and move_tokens and hspeed > 1e-6:
            sneak_speeds.append(hspeed)
            if hspeed < 0.03:
                ultra_slow_sneak_ticks += 1

        if rmb_key and not prev_rmb:
            rmb_total += 1
            if snk_key or crouching:
                rmb_clicks_while_sneak += 1
                if last_rmb_snk_t is not None and 1 <= (t - last_rmb_snk_t) <= 40:
                    rmb_snk_intervals.append(t - last_rmb_snk_t)
                last_rmb_snk_t = t
        prev_rmb = rmb_key

        hp = float(tk.get("hp", 20.0))
        hurt = int(tk.get("hurt", 0) or 0)
        void_dist = float(tk.get("voidDist", 30.0))
        if prev_hp is not None and hp <= 0.0 < prev_hp:
            deaths += 1
            if void_dist <= 4.0:
                void_falls += 1
            events_timeline.append((t, f"DEATH (voidDist={void_dist:.1f})"))
        if hurt >= 9 and prev_hurt < 9:
            hits_taken += 1
        prev_hp = hp
        prev_hurt = hurt

        inv = tk.get("inv") or {}
        sword = int(inv.get("sword", 1) or 1)
        armor = float(inv.get("armor", 7.0) or 0.0)
        blocks = int(inv.get("blocks", 0) or 0)
        max_sword = max(max_sword, sword)
        max_armor = max(max_armor, armor)
        if sword > prev_sword and first_sword_upgrade_t is None:
            first_sword_upgrade_t = t
            events_timeline.append((t, f"SWORD tier {prev_sword}->{sword}"))
        if armor > prev_armor + 0.5 and first_armor_upgrade_t is None:
            first_armor_upgrade_t = t
            events_timeline.append((t, f"ARMOR {prev_armor:.1f}->{armor:.1f}"))
        if prev_blocks > 0 and blocks < prev_blocks and first_block_drop_t is None and t > 10:
            first_block_drop_t = t
            events_timeline.append((t, f"FIRST_BLOCK_PLACE blocks {prev_blocks}->{blocks}"))
        prev_sword = sword
        prev_armor = armor
        prev_blocks = blocks

    # Attack cadence & LMB+RMB overlaps
    atk_dts: List[int] = []
    dt1_bursts = 0
    cur_dt1_run = 0
    max_dt1_run = 0
    lmb_rmb_overlap_atks = 0
    atk_on_block_or_miss = 0
    atk_cds: List[float] = []
    prev_atk_t: Optional[int] = None

    for af in attack_fires:
        at = af["t"]
        kv = af["kv"]
        hr = kv.get("hitResult", "")
        if hr in ("BLOCK", "MISS"):
            atk_on_block_or_miss += 1
        if "cd" in kv:
            try:
                atk_cds.append(float(kv["cd"]))
            except ValueError:
                pass
        if prev_atk_t is not None:
            dt = at - prev_atk_t
            atk_dts.append(dt)
            if dt == 1:
                dt1_bursts += 1
                cur_dt1_run += 1
                max_dt1_run = max(max_dt1_run, cur_dt1_run)
            else:
                cur_dt1_run = 0
        prev_atk_t = at

        tk = ticks.get(at)
        if tk is not None:
            kstr = str(tk.get("keys", "") or "")
            if "RMB" in kstr or tk.get("using"):
                lmb_rmb_overlap_atks += 1

    # Keys LMB+RMB overlap ticks across whole session
    lmb_rmb_key_ticks = sum(
        1 for t in sorted_t
        if "LMB" in str(ticks[t].get("keys", "")) and "RMB" in str(ticks[t].get("keys", ""))
    )

    # Clutch burst density (max clutches in any 20-tick window)
    clutch_times = [c["t"] for c in autoclutches]
    max_clutch_window = 0
    for i, t0 in enumerate(clutch_times):
        cnt = sum(1 for t1 in clutch_times[i:] if t1 - t0 <= 20)
        max_clutch_window = max(max_clutch_window, cnt)

    # First shop summary & iron left after first shop sequence
    first_shop_t = shop_clicks[0]["t"] if shop_clicks else None
    post_warmup_tp = [tp for tp in teleports if tp["t"] > 20]
    sneak_speeds_sorted = sorted(sneak_speeds)
    events_timeline.sort(key=lambda x: x[0])

    return {
        "meta": meta,
        "economy": {
            "shopClicks": len(shop_clicks),
            "firstShopTick": first_shop_t,
            "firstBlockPlaceTick": first_block_drop_t,
            "firstSwordTick": first_sword_upgrade_t,
            "firstArmorTick": first_armor_upgrade_t,
            "maxSwordTier": max_sword,
            "maxArmorPoints": max_armor,
            "boughtItems": [
                f"t={s.get('t')}:{s.get('item') or s.get('buyKey') or s.get('slot')}"
                for s in shop_clicks[:10]
            ],
            "matchUpgrades": {
                "sharp": latest_match_state.get("sharp", 0),
                "prot": latest_match_state.get("prot", 0),
                "forge": latest_match_state.get("forge", 0),
                "healPool": latest_match_state.get("healPool", False),
                "traps": latest_match_state.get("traps", 0),
            },
        },
        "movement": {
            "sneakMoveTicks": len(sneak_speeds_sorted),
            "sneakSpeedP50": percentile(sneak_speeds_sorted, 0.50),
            "sneakSpeedP95": percentile(sneak_speeds_sorted, 0.95),
            "sneakSpeedMin": sneak_speeds_sorted[0] if sneak_speeds_sorted else None,
            "rmbTotal": rmb_total,
            "rmbSneakClicks": rmb_clicks_while_sneak,
            "rmbSneakIntervalP50": percentile(sorted(rmb_snk_intervals), 0.50),
            "deaths": deaths,
            "voidFalls": void_falls,
            "hitsTaken": hits_taken,
        },
        "combat": {
            "attacksFired": len(attack_fires),
            "avgAtkCooldown": (sum(atk_cds) / len(atk_cds)) if atk_cds else None,
            "attackDt1Bursts": dt1_bursts,
            "maxDt1Run": max_dt1_run,
            "lmbRmbOverlapAtks": lmb_rmb_overlap_atks,
            "atkBlockOrMiss": atk_on_block_or_miss,
            "blockPlaceEvents": len(block_places),
            "clutchAttempts": len(autoclutches),
            "maxClutchIn20Ticks": max_clutch_window,
        },
        "anticheat": {
            "serverTeleportsTotal": len(teleports),
            "serverTeleportsPostSpawn": len(post_warmup_tp),
            "ultraSlowSneakTicks": ultra_slow_sneak_ticks,
            "sprSnkKeyTicks": spr_snk_key_ticks,
            "sprSnkEntityTicks": spr_snk_entity_ticks,
            "lmbRmbKeyOverlapTicks": lmb_rmb_key_ticks,
            "dt1AttackBursts": dt1_bursts,
            "lmbRmbOverlapAtks": lmb_rmb_overlap_atks,
            "maxClutchIn20Ticks": max_clutch_window,
        },
        "maneuvers": {
            "datasetRows": dataset_rows,
            "teacherAgreePct": (100.0 * teacher_matches / dataset_rows) if dataset_rows else None,
            "top": maneuver_counts.most_common(6),
        },
        "timeline": events_timeline[:25],
    }


def format_side_by_side(a: Dict[str, Any], b: Optional[Dict[str, Any]]) -> str:
    lines: List[str] = []
    la = f"{a['meta']['source'].upper()} ({a['meta']['map']})"
    lb = f"{b['meta']['source'].upper()} ({b['meta']['map']})" if b else ""

    def row(label: str, va: Any, vb: Any = "") -> None:
        if b is None:
            lines.append(f"  {label:<32} | {str(va):<36}")
        else:
            lines.append(f"  {label:<32} | {str(va):<32} | {str(vb):<32}")

    header = f"  {'METRIC':<32} | {la:<32}" + (f" | {lb:<32}" if b else "")
    sep = "-" * len(header)
    lines.append(sep)
    lines.append(header)
    lines.append(sep)

    row("File", a["meta"]["file"][:30], b["meta"]["file"][:30] if b else "")
    row("Ruleset / Team", f"{a['meta']['ruleset']} / {a['meta']['homeTeam']}",
        f"{b['meta']['ruleset']} / {b['meta']['homeTeam']}" if b else "")
    row("Duration (ticks / sec)", f"{a['meta']['ticks']}t / {a['meta']['durationSec']:.1f}s",
        f"{b['meta']['ticks']}t / {b['meta']['durationSec']:.1f}s" if b else "")

    lines.append(sep)
    row("First Shop Click (tick)", a["economy"]["firstShopTick"], b["economy"]["firstShopTick"] if b else "")
    row("Shop Clicks Total", a["economy"]["shopClicks"], b["economy"]["shopClicks"] if b else "")
    row("First Block Place (tick)", a["economy"]["firstBlockPlaceTick"], b["economy"]["firstBlockPlaceTick"] if b else "")
    row("First Sword Upgrade (tick)", a["economy"]["firstSwordTick"], b["economy"]["firstSwordTick"] if b else "")
    row("First Armor Upgrade (tick)", a["economy"]["firstArmorTick"], b["economy"]["firstArmorTick"] if b else "")
    row("Max Sword / Max Armor", f"T{a['economy']['maxSwordTier']} / {a['economy']['maxArmorPoints']:.1f}",
        f"T{b['economy']['maxSwordTier']} / {b['economy']['maxArmorPoints']:.1f}" if b else "")
    row("Team Upg (S/P/F/H/T)",
        f"{a['economy']['matchUpgrades']['sharp']}/{a['economy']['matchUpgrades']['prot']}/{a['economy']['matchUpgrades']['forge']}/{int(bool(a['economy']['matchUpgrades']['healPool']))}/{a['economy']['matchUpgrades']['traps']}",
        f"{b['economy']['matchUpgrades']['sharp']}/{b['economy']['matchUpgrades']['prot']}/{b['economy']['matchUpgrades']['forge']}/{int(bool(b['economy']['matchUpgrades']['healPool']))}/{b['economy']['matchUpgrades']['traps']}" if b else "")

    lines.append(sep)
    row("Sneak Move Ticks", a["movement"]["sneakMoveTicks"], b["movement"]["sneakMoveTicks"] if b else "")
    row("Sneak Speed p50 (b/t)", fmt_val(a["movement"]["sneakSpeedP50"]),
        fmt_val(b["movement"]["sneakSpeedP50"]) if b else "")
    row("Sneak Speed min (b/t)", fmt_val(a["movement"]["sneakSpeedMin"]),
        fmt_val(b["movement"]["sneakSpeedMin"]) if b else "")
    row("Sneak RMB Interval p50 (t)", fmt_val(a["movement"]["rmbSneakIntervalP50"], ".1f"),
        fmt_val(b["movement"]["rmbSneakIntervalP50"], ".1f") if b else "")
    row("Hits Taken / Deaths (Void)",
        f"{a['movement']['hitsTaken']} / {a['movement']['deaths']} ({a['movement']['voidFalls']})",
        f"{b['movement']['hitsTaken']} / {b['movement']['deaths']} ({b['movement']['voidFalls']})" if b else "")

    lines.append(sep)
    row("Attacks Fired", a["combat"]["attacksFired"], b["combat"]["attacksFired"] if b else "")
    row("Avg Cooldown on Swing", fmt_val(a["combat"]["avgAtkCooldown"], ".2f"),
        fmt_val(b["combat"]["avgAtkCooldown"], ".2f") if b else "")
    row("Attack Block/Miss Hits", a["combat"]["atkBlockOrMiss"], b["combat"]["atkBlockOrMiss"] if b else "")
    row("Autoclutch Attempts", a["combat"]["clutchAttempts"], b["combat"]["clutchAttempts"] if b else "")

    lines.append(sep)
    lines.append("  [ANTI-CHEAT / VANILLA SANITY AUDIT]")
    row("SERVER_TELEPORT (post-spawn)", a["anticheat"]["serverTeleportsPostSpawn"],
        b["anticheat"]["serverTeleportsPostSpawn"] if b else "")
    row("Ultra-Slow Sneak (<0.03 b/t)", a["anticheat"]["ultraSlowSneakTicks"],
        b["anticheat"]["ultraSlowSneakTicks"] if b else "")
    row("SPR+SNK Keys / Entity",
        f"{a['anticheat']['sprSnkKeyTicks']} / {a['anticheat']['sprSnkEntityTicks']}",
        f"{b['anticheat']['sprSnkKeyTicks']} / {b['anticheat']['sprSnkEntityTicks']}" if b else "")
    row("dt==1 Attack Bursts (MaxRun)",
        f"{a['anticheat']['dt1AttackBursts']} (max {a['combat']['maxDt1Run']})",
        f"{b['anticheat']['dt1AttackBursts']} (max {b['combat']['maxDt1Run']})" if b else "")
    row("Same-Tick LMB+RMB on Attack", a["anticheat"]["lmbRmbOverlapAtks"],
        b["anticheat"]["lmbRmbOverlapAtks"] if b else "")
    row("LMB+RMB Key Overlap Ticks", a["anticheat"]["lmbRmbKeyOverlapTicks"],
        b["anticheat"]["lmbRmbKeyOverlapTicks"] if b else "")
    row("Max Clutch Burst / 20t", a["anticheat"]["maxClutchIn20Ticks"],
        b["anticheat"]["maxClutchIn20Ticks"] if b else "")
    lines.append(sep)

    lines.append(f"  First Shop Clicks [{la}]: " + ", ".join(a["economy"]["boughtItems"]))
    if b:
        lines.append(f"  First Shop Clicks [{lb}]: " + ", ".join(b["economy"]["boughtItems"]))

    if a["maneuvers"]["datasetRows"]:
        lines.append(f"  Top Maneuvers [{la}] (rows={a['maneuvers']['datasetRows']}, teacherAgree={fmt_val(a['maneuvers']['teacherAgreePct'], '.1f')}%): "
                     + ", ".join(f"{k}={v}" for k, v in a["maneuvers"]["top"]))
    if b and b["maneuvers"]["datasetRows"]:
        lines.append(f"  Top Maneuvers [{lb}] (rows={b['maneuvers']['datasetRows']}, teacherAgree={fmt_val(b['maneuvers']['teacherAgreePct'], '.1f')}%): "
                     + ", ".join(f"{k}={v}" for k, v in b["maneuvers"]["top"]))

    lines.append(f"\n  [PARITY SCORECARD vs WINNING HUMAN TARGETS]")
    def _hold_pct(rep: Dict[str, Any]) -> Optional[float]:
        rows = rep["maneuvers"]["datasetRows"]
        if not rows:
            return None
        for k, v in rep["maneuvers"]["top"]:
            if k == "HOLD_HOME_GEN":
                return (100.0 * v) / rows
        return 0.0
    for lbl, rep in [(la, a)] + ([(lb, b)] if b else []):
        hpct = _hold_pct(rep)
        lines.append(
            f"    [{lbl}] HOLD_HOME_GEN={fmt_val(hpct, '.1f')}% (target <=35%) | "
            f"Shop={rep['economy']['shopClicks']} | "
            f"SneakSpd p50={fmt_val(rep['movement']['sneakSpeedP50'], '.4f')} (target ~0.0415) | "
            f"UltraSlowSneak={rep['anticheat']['ultraSlowSneakTicks']} | "
            f"TP={rep['anticheat']['serverTeleportsPostSpawn']}"
        )

    lines.append(f"\n  [TIMELINE SAMPLE - {la}]")
    for t, ev in a["timeline"][:15]:
        lines.append(f"    t={t:<6d} ({t/20.0:>5.1f}s)  {ev}")
    if b:
        lines.append(f"\n  [TIMELINE SAMPLE - {lb}]")
        for t, ev in b["timeline"][:15]:
            lines.append(f"    t={t:<6d} ({t/20.0:>5.1f}s)  {ev}")

    return "\n".join(lines)


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Compare BedWars human vs bot blackbox JSONL recordings.")
    parser.add_argument("session_a", type=Path, help="Path to first diag-*.jsonl recording (e.g. human)")
    parser.add_argument("session_b", type=Path, nargs="?", default=None, help="Optional second diag-*.jsonl recording (e.g. bot)")
    args = parser.parse_args(argv)

    if not args.session_a.is_file():
        print(f"Error: file not found: {args.session_a}", file=sys.stderr)
        return 2
    if args.session_b is not None and not args.session_b.is_file():
        print(f"Error: file not found: {args.session_b}", file=sys.stderr)
        return 2

    res_a = analyze_session(args.session_a)
    res_b = analyze_session(args.session_b) if args.session_b is not None else None
    print(format_side_by_side(res_a, res_b))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
