#!/usr/bin/env python3
"""
Pathadol Neuro-Symbolic Brain Trainer (Google Colab Pro L4 GPU & Local Smoke-Test)
================================================================================
Trains a recurrent PyTorch policy-value network (`PathadolBrainNet`) matching
`baritone.brain.BrainSchema` (OBS_DIM=72) and `baritone.brain.BrainActionSpace`
(13 tactical maneuvers, 4 continuous style sliders, 4 combat modes), using:

  1. Behavioral Cloning (BC) warm-start from `.minecraft/pathadol/brain_data/*.jsonl`
     and/or built-in teacher rollouts.
  2. Batched Vectorized 4-Team Bedwars + PvP Macro Gym + Masked PPO fine-tuning.
  3. Zero-dependency `.pbrain` JSON+Base64 float32 export with exact PyTorch parity
     checks against the pure-Java `NeuralPolicyEngine` forward equations.
"""

from __future__ import annotations

import argparse
import base64
import glob
import json
import math
import os
import time
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

SCHEMA_VERSION = 1
OBS_DIM = 72
NUM_MANEUVERS = 13
NUM_SLIDERS = 4
NUM_COMBAT_MODES = 4

MANEUVER_NAMES = [
    "HOLD_HOME_GEN",
    "SHOP_BUY_ESSENTIALS",
    "SHOP_BUY_COMBAT_UPGRADES",
    "DEFEND_HOME_BED",
    "RUSH_NEAREST_BED",
    "RUSH_WEAKEST_BED",
    "FARM_DIAMONDS",
    "FARM_EMERALDS",
    "STASH_VALUABLES",
    "ENGAGE_THREAT_COMBAT",
    "RETREAT_HOME",
    "FIREBALL_JUMP",
    "TACTICAL_VOID_RECALL",
]

COMBAT_MODE_NAMES = [
    "BALANCED",
    "VOID_KNOCKBACK",
    "CRIT_DUEL",
    "DEFENSIVE_KITE",
]


class PathadolBrainNet(nn.Module):
    """
    Exact PyTorch architecture mirrored by Java `baritone.brain.NeuralPolicyEngine`:
      obs[72] -> standardize -> (Linear + LayerNorm + SiLU) x 2 -> GRUCell(128) ->
      { maneuver logits [13], slider raw [4], combat logits [4], value [1] }.
    """

    def __init__(self, hidden_dim: int = 128, enc_dim: int = 192) -> None:
        super().__init__()
        self.obs_dim = OBS_DIM
        self.hidden_dim = hidden_dim
        self.register_buffer("obs_mean", torch.zeros(OBS_DIM, dtype=torch.float32))
        self.register_buffer("obs_std", torch.ones(OBS_DIM, dtype=torch.float32))

        self.enc_fc1 = nn.Linear(OBS_DIM, enc_dim)
        self.enc_ln1 = nn.LayerNorm(enc_dim, eps=1e-5)
        self.enc_fc2 = nn.Linear(enc_dim, hidden_dim)
        self.enc_ln2 = nn.LayerNorm(hidden_dim, eps=1e-5)

        self.gru = nn.GRUCell(hidden_dim, hidden_dim)

        self.maneuver_head = nn.Linear(hidden_dim, NUM_MANEUVERS)
        self.slider_head = nn.Linear(hidden_dim, NUM_SLIDERS)
        self.combat_head = nn.Linear(hidden_dim, NUM_COMBAT_MODES)
        self.value_head = nn.Linear(hidden_dim, 1)

    def encode(self, obs: torch.Tensor) -> torch.Tensor:
        x = (obs - self.obs_mean) / torch.clamp(self.obs_std, min=1e-4)
        x = F.silu(self.enc_ln1(self.enc_fc1(x)))
        x = F.silu(self.enc_ln2(self.enc_fc2(x)))
        return x

    def forward_step(
        self,
        obs: torch.Tensor,
        h: torch.Tensor,
    ) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
        feat = self.encode(obs)
        h_next = self.gru(feat, h)
        m_logits = self.maneuver_head(h_next)
        sliders = torch.sigmoid(self.slider_head(h_next))
        c_logits = self.combat_head(h_next)
        value = self.value_head(h_next).squeeze(-1)
        return m_logits, sliders, c_logits, value, h_next

    def init_hidden(self, batch_size: int, device: torch.device) -> torch.Tensor:
        return torch.zeros(batch_size, self.hidden_dim, dtype=torch.float32, device=device)


def _tensor_to_f32_b64(t: torch.Tensor) -> Dict[str, str]:
    arr = np.ascontiguousarray(t.detach().cpu().numpy().astype("<f4"))
    return {"f32_b64": base64.b64encode(arr.tobytes()).decode("ascii")}


def _linear_to_json(linear: nn.Linear, ln: Optional[nn.LayerNorm] = None) -> Dict[str, object]:
    out_dim, in_dim = linear.weight.shape
    obj: Dict[str, object] = {
        "out_dim": int(out_dim),
        "in_dim": int(in_dim),
        "weight": _tensor_to_f32_b64(linear.weight.reshape(-1)),
        "bias": _tensor_to_f32_b64(linear.bias.reshape(-1)),
    }
    if ln is not None:
        obj["ln_weight"] = _tensor_to_f32_b64(ln.weight.reshape(-1))
        obj["ln_bias"] = _tensor_to_f32_b64(ln.bias.reshape(-1))
    return obj


def export_pbrain(model: PathadolBrainNet, out_path: str, name: str = "pathadol-bw-l4") -> None:
    model.eval()
    bundle = {
        "schema_version": SCHEMA_VERSION,
        "obs_dim": OBS_DIM,
        "name": name,
        "activation": "silu",
        "obs_mean": _tensor_to_f32_b64(model.obs_mean),
        "obs_std": _tensor_to_f32_b64(model.obs_std),
        "encoder_layers": [
            _linear_to_json(model.enc_fc1, model.enc_ln1),
            _linear_to_json(model.enc_fc2, model.enc_ln2),
        ],
        "gru": {
            "input_size": int(model.gru.input_size),
            "hidden_size": int(model.gru.hidden_size),
            "weight_ih": _tensor_to_f32_b64(model.gru.weight_ih.reshape(-1)),
            "bias_ih": _tensor_to_f32_b64(model.gru.bias_ih.reshape(-1)),
            "weight_hh": _tensor_to_f32_b64(model.gru.weight_hh.reshape(-1)),
            "bias_hh": _tensor_to_f32_b64(model.gru.bias_hh.reshape(-1)),
        },
        "heads": {
            "maneuver": _linear_to_json(model.maneuver_head),
            "sliders": _linear_to_json(model.slider_head),
            "combat": _linear_to_json(model.combat_head),
            "value": _linear_to_json(model.value_head),
        },
    }
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(bundle, f, separators=(",", ":"))


def verify_java_math_parity(model: PathadolBrainNet, pbrain_path: str) -> float:
    """
    Executes exact scalar numpy replicas of `NeuralPolicyEngine.java` (`applyLayerNorm`, `SILU`,
    `forwardGruCell`) directly from the saved `.pbrain` JSON and asserts parity with PyTorch.
    """
    with open(pbrain_path, "r", encoding="utf-8") as f:
        doc = json.load(f)

    def dec(obj: Dict[str, str], shape: Optional[Tuple[int, ...]] = None) -> np.ndarray:
        raw = base64.b64decode(obj["f32_b64"])
        arr = np.frombuffer(raw, dtype="<f4").astype(np.float64)
        return arr.reshape(shape) if shape else arr

    rng = np.random.default_rng(1337)
    x_raw = rng.uniform(0.0, 1.0, size=(OBS_DIM,)).astype(np.float32)
    h_prev = np.zeros((model.hidden_dim,), dtype=np.float64)

    mean = dec(doc["obs_mean"])
    std = np.maximum(dec(doc["obs_std"]), 1e-4)
    cur = (x_raw.astype(np.float64) - mean) / std

    for layer in doc["encoder_layers"]:
        o, i = layer["out_dim"], layer["in_dim"]
        w = dec(layer["weight"], (o, i))
        b = dec(layer["bias"])
        cur = w @ cur + b
        if "ln_weight" in layer:
            m = cur.mean()
            v = ((cur - m) ** 2).mean()
            cur = ((cur - m) / math.sqrt(v + 1e-5)) * dec(layer["ln_weight"]) + dec(layer["ln_bias"])
        cur = cur / (1.0 + np.exp(-np.clip(cur, -20.0, 20.0)))  # SiLU

    g = doc["gru"]
    H, I = g["hidden_size"], g["input_size"]
    gi = dec(g["weight_ih"], (3 * H, I)) @ cur + dec(g["bias_ih"])
    gh = dec(g["weight_hh"], (3 * H, H)) @ h_prev + dec(g["bias_hh"])
    r = 1.0 / (1.0 + np.exp(-np.clip(gi[:H] + gh[:H], -20.0, 20.0)))
    z = 1.0 / (1.0 + np.exp(-np.clip(gi[H : 2 * H] + gh[H : 2 * H], -20.0, 20.0)))
    n = np.tanh(gi[2 * H :] + r * gh[2 * H :])
    h_np = (1.0 - z) * n + z * h_prev

    mh = doc["heads"]["maneuver"]
    logits_np = dec(mh["weight"], (mh["out_dim"], mh["in_dim"])) @ h_np + dec(mh["bias"])
    vh = doc["heads"]["value"]
    val_np = (dec(vh["weight"], (vh["out_dim"], vh["in_dim"])) @ h_np + dec(vh["bias"]))[0]

    model_cpu = model.cpu().eval()
    with torch.no_grad():
        t_obs = torch.from_numpy(x_raw).unsqueeze(0)
        t_h = model_cpu.init_hidden(1, torch.device("cpu"))
        t_m, _, _, t_val, _ = model_cpu.forward_step(t_obs, t_h)
        max_err = max(
            float(np.max(np.abs(logits_np - t_m[0].numpy()))),
            float(abs(val_np - float(t_val[0].numpy()))),
        )
    if max_err > 1e-4:
        raise AssertionError(f"Parity check failed: max_err={max_err}")
    return max_err


class VectorizedBedwarsGym:
    """
    Fast vectorized 4-team Bedwars + PvP macro simulator outputting exact 72-float
    `BrainSchema` observations and step rewards aligned with `StepRewardCalculator`.
    1 macro decision step = 10 Minecraft ticks (~0.5 seconds).
    """

    def __init__(self, num_envs: int = 256, max_steps: int = 260, seed: int = 42) -> None:
        self.num_envs = num_envs
        self.max_steps = max_steps
        self.rng = np.random.default_rng(seed)
        self.reset_all()

    def reset_all(self) -> Tuple[np.ndarray, np.ndarray]:
        n = self.num_envs
        self.step_idx = np.zeros(n, dtype=np.int32)
        self.health = np.full(n, 20.0, dtype=np.float32)
        self.absorption = np.zeros(n, dtype=np.float32)
        self.iron = self.rng.integers(0, 8, size=n).astype(np.float32)
        self.gold = np.zeros(n, dtype=np.float32)
        self.diamonds = np.zeros(n, dtype=np.float32)
        self.emeralds = np.zeros(n, dtype=np.float32)
        self.wool = self.rng.integers(0, 16, size=n).astype(np.float32)
        self.hard_blocks = np.zeros(n, dtype=np.float32)
        self.sword_tier = np.ones(n, dtype=np.float32)
        self.pick_tier = np.zeros(n, dtype=np.float32)
        self.axe_tier = np.zeros(n, dtype=np.float32)
        self.shears = np.zeros(n, dtype=np.float32)
        self.armor_pts = np.full(n, 7.0, dtype=np.float32)
        self.gapples = np.zeros(n, dtype=np.float32)
        self.fireballs = np.zeros(n, dtype=np.float32)
        self.tnt = np.zeros(n, dtype=np.float32)

        self.home_bed_alive = np.ones(n, dtype=bool)
        self.home_def_ratio = np.zeros(n, dtype=np.float32)
        self.enemy_beds = np.full(n, 3, dtype=np.int32)
        self.weakest_bed_sec = self.rng.uniform(0.8, 3.2, size=n).astype(np.float32)
        self.nearest_bed_sec = np.maximum(self.weakest_bed_sec, self.rng.uniform(1.5, 5.5, size=n)).astype(np.float32)

        # Zone: 0=HomeIsland, 1=DiamondIsland, 2=EmeraldMid, 3=EnemyBase, 4=BridgeCorridor
        self.dist_home = self.rng.uniform(2.0, 7.0, size=n).astype(np.float32)
        self.dist_dia = self.rng.uniform(24.0, 38.0, size=n).astype(np.float32)
        self.dist_em = self.rng.uniform(42.0, 62.0, size=n).astype(np.float32)
        self.dist_enemy_bed = self.rng.uniform(34.0, 56.0, size=n).astype(np.float32)
        self.threat_present = np.zeros(n, dtype=bool)
        self.threat_dist = np.full(n, 48.0, dtype=np.float32)
        self.threat_hp = np.full(n, 20.0, dtype=np.float32)
        self.enemy_near_home = np.zeros(n, dtype=bool)

        self.active_maneuver = np.zeros(n, dtype=np.int32)
        self.hold_ticks = np.zeros(n, dtype=np.int32)
        return self._build_obs()

    def _reset_mask(self, done: np.ndarray) -> None:
        if not np.any(done):
            return
        idx = np.where(done)[0]
        self.step_idx[idx] = 0
        self.health[idx] = 20.0
        self.absorption[idx] = 0.0
        self.iron[idx] = self.rng.integers(0, 8, size=len(idx)).astype(np.float32)
        self.gold[idx] = 0.0
        self.diamonds[idx] = 0.0
        self.emeralds[idx] = 0.0
        self.wool[idx] = self.rng.integers(0, 16, size=len(idx)).astype(np.float32)
        self.hard_blocks[idx] = 0.0
        self.sword_tier[idx] = 1.0
        self.pick_tier[idx] = 0.0
        self.axe_tier[idx] = 0.0
        self.shears[idx] = 0.0
        self.armor_pts[idx] = 7.0
        self.gapples[idx] = 0.0
        self.fireballs[idx] = 0.0
        self.tnt[idx] = 0.0
        self.home_bed_alive[idx] = True
        self.home_def_ratio[idx] = 0.0
        self.enemy_beds[idx] = 3
        self.weakest_bed_sec[idx] = self.rng.uniform(0.8, 3.2, size=len(idx)).astype(np.float32)
        self.nearest_bed_sec[idx] = np.maximum(self.weakest_bed_sec[idx], self.rng.uniform(1.5, 5.5, size=len(idx))).astype(np.float32)
        self.dist_home[idx] = self.rng.uniform(2.0, 7.0, size=len(idx)).astype(np.float32)
        self.dist_dia[idx] = self.rng.uniform(24.0, 38.0, size=len(idx)).astype(np.float32)
        self.dist_em[idx] = self.rng.uniform(42.0, 62.0, size=len(idx)).astype(np.float32)
        self.dist_enemy_bed[idx] = self.rng.uniform(34.0, 56.0, size=len(idx)).astype(np.float32)
        self.threat_present[idx] = False
        self.threat_dist[idx] = 48.0
        self.threat_hp[idx] = 20.0
        self.enemy_near_home[idx] = False
        self.active_maneuver[idx] = 0
        self.hold_ticks[idx] = 0

    def _build_obs(self) -> Tuple[np.ndarray, np.ndarray]:
        n = self.num_envs
        obs = np.zeros((n, OBS_DIM), dtype=np.float32)

        obs[:, 0] = np.clip(self.health / 20.0, 0.0, 1.0)
        obs[:, 1] = np.clip(self.absorption / 20.0, 0.0, 1.0)
        obs[:, 2] = 1.0  # food
        obs[:, 3] = 1.0  # on_ground
        obs[:, 4] = (self.dist_home > 12.0).astype(np.float32)  # sprinting
        obs[:, 7] = np.where(self.dist_home > 10.0, 0.62, 0.25)  # horiz speed
        obs[:, 10] = 0.55  # void clearance
        obs[:, 11] = 0.75  # ceiling clearance

        obs[:, 14] = np.clip(self.iron / 64.0, 0.0, 1.0)
        obs[:, 15] = np.clip(self.gold / 32.0, 0.0, 1.0)
        obs[:, 16] = np.clip(self.diamonds / 16.0, 0.0, 1.0)
        obs[:, 17] = np.clip(self.emeralds / 16.0, 0.0, 1.0)
        obs[:, 18] = np.clip(self.wool / 64.0, 0.0, 1.0)
        obs[:, 19] = np.clip(self.hard_blocks / 32.0, 0.0, 1.0)
        obs[:, 20] = np.clip(self.sword_tier / 4.0, 0.0, 1.0)
        obs[:, 21] = np.clip(self.pick_tier / 4.0, 0.0, 1.0)
        obs[:, 22] = np.clip(self.axe_tier / 4.0, 0.0, 1.0)
        obs[:, 23] = self.shears
        obs[:, 24] = np.clip(self.armor_pts / 20.0, 0.0, 1.2)
        obs[:, 25] = np.clip(self.gapples / 8.0, 0.0, 1.0)
        obs[:, 26] = np.clip(self.fireballs / 4.0, 0.0, 1.0)
        obs[:, 27] = np.clip(self.tnt / 4.0, 0.0, 1.0)

        obs[:, 30] = self.home_bed_alive.astype(np.float32)
        obs[:, 31] = np.clip(self.home_def_ratio, 0.0, 1.0)
        obs[:, 32] = np.clip(self.dist_home / 128.0, 0.0, 1.0)
        obs[:, 34] = 1.0
        obs[:, 36] = np.clip(self.enemy_beds / 7.0, 0.0, 1.0)
        obs[:, 37] = np.clip(self.dist_enemy_bed / 128.0, 0.0, 1.0)
        obs[:, 39] = 1.0
        obs[:, 40] = np.clip(self.nearest_bed_sec / 15.0, 0.0, 1.0)
        obs[:, 41] = np.clip(self.weakest_bed_sec / 15.0, 0.0, 1.0)
        obs[:, 42] = np.clip(self.dist_dia / 128.0, 0.0, 1.0)
        obs[:, 44] = 1.0
        obs[:, 45] = np.clip(self.dist_em / 128.0, 0.0, 1.0)
        obs[:, 47] = 1.0

        obs[:, 48] = self.threat_present.astype(np.float32)
        obs[:, 49] = np.where(self.threat_present, np.clip(self.threat_dist / 64.0, 0.0, 1.0), 1.0)
        obs[:, 51] = 1.0
        obs[:, 52] = np.where(self.threat_present, 0.4, 0.0)
        obs[:, 53] = np.where(self.threat_present, np.clip(1.0 - self.threat_dist / 24.0, 0.0, 1.0), 0.0)
        obs[:, 55] = 1.0
        obs[:, 57] = 1.0
        obs[:, 61] = 1.0
        obs[:, 63] = 1.0

        on_bridge = (self.dist_home > 14.0) & (self.dist_enemy_bed > 10.0)
        obs[:, 66] = np.where(on_bridge, 0.25, 0.90)
        obs[:, 67] = np.where(on_bridge, 0.65, 0.15)
        obs[:, 68] = np.clip(self.active_maneuver / 12.0, 0.0, 1.0)
        obs[:, 69] = np.clip(self.hold_ticks / 200.0, 0.0, 1.0)
        obs[:, 70] = (self.active_maneuver != 0).astype(np.float32)
        obs[:, 71] = (self.dist_home <= 14.0).astype(np.float32)

        mask = np.ones((n, NUM_MANEUVERS), dtype=bool)
        close_melee = self.threat_present & (self.threat_dist < 4.5)
        mask[:, 1] = (~close_melee) & (self.iron >= 4.0)
        mask[:, 2] = (~close_melee) & ((self.gold >= 3.0) | (self.diamonds >= 2.0) | (self.iron >= 40.0) | (self.emeralds >= 4.0))
        mask[:, 3] = self.home_bed_alive & (self.home_def_ratio < 0.95) & ((self.wool > 0) | (self.hard_blocks > 0))
        mask[:, 4] = (self.enemy_beds > 0) & (self.wool >= 4.0)
        mask[:, 5] = (self.enemy_beds > 0) & (self.wool >= 4.0)
        has_vals = (self.diamonds > 0) | (self.emeralds > 0) | (self.gold >= 6.0) | (self.iron >= 24.0)
        mask[:, 8] = has_vals & (self.dist_home < 90.0)
        mask[:, 9] = self.threat_present & (self.threat_dist <= 32.0)
        safe_void = (self.diamonds == 0) & (self.emeralds == 0) & (self.gold < 6.0) & (self.iron < 24.0)
        mask[:, 10] = (self.dist_home > 14.0) | (self.threat_present & (self.threat_dist < 12.0) & (self.health < 11.0))
        mask[:, 11] = (self.fireballs > 0) & (~close_melee)
        mask[:, 12] = self.home_bed_alive & (self.dist_home > 26.0) & safe_void
        return obs, mask

    def step(
        self,
        maneuvers: np.ndarray,
        sliders: np.ndarray,
        combat_modes: np.ndarray,
    ) -> Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
        n = self.num_envs
        self.step_idx += 1
        rewards = np.full(n, -0.002, dtype=np.float32)

        switched = maneuvers != self.active_maneuver
        self.hold_ticks = np.where(switched, 0, self.hold_ticks + 10)
        self.active_maneuver = maneuvers.copy()

        risk = np.clip(sliders[:, 0], 0.0, 1.0)
        speed = np.clip(sliders[:, 1], 0.0, 1.0)
        caution = np.clip(sliders[:, 2], 0.0, 1.0)
        aggr = np.clip(sliders[:, 3], 0.0, 1.0)

        # Gentle passive home forge tick when near base
        at_home = self.dist_home <= 10.0
        self.iron += np.where(at_home, 1.6, 0.0)
        self.gold += np.where(at_home & (self.step_idx % 2 == 0), 0.6, 0.0)

        # Gentle passive regen
        self.health = np.minimum(20.0, self.health + 0.12)

        # Action outcomes
        # 0: HOLD_HOME_GEN
        m0 = maneuvers == 0
        self.dist_home = np.where(m0, np.maximum(2.0, self.dist_home - 3.5), self.dist_home)
        self.iron += np.where(m0 & (self.dist_home <= 6.0), 3.0, 0.0)
        self.gold += np.where(m0 & (self.dist_home <= 6.0), 0.7, 0.0)
        rewards += np.where(m0 & (self.dist_home <= 6.0), 0.02, 0.0)

        # 1 & 2: SHOP_BUY_ESSENTIALS / SHOP_BUY_COMBAT_UPGRADES
        m12 = (maneuvers == 1) | (maneuvers == 2)
        self.dist_home = np.where(m12, np.maximum(3.0, self.dist_home - 4.0), self.dist_home)
        can_shop = m12 & (self.dist_home <= 14.0)
        bought_wool = can_shop & (self.iron >= 4.0) & (self.wool < 40.0)
        self.iron -= np.where(bought_wool, 4.0, 0.0)
        self.wool += np.where(bought_wool, 16.0, 0.0)
        bought_sword = can_shop & (self.gold >= 7.0) & (self.sword_tier < 3.0)
        self.gold -= np.where(bought_sword, 7.0, 0.0)
        self.sword_tier = np.where(bought_sword, 3.0, self.sword_tier)
        rewards += np.where(bought_sword, 0.45, 0.0)
        bought_armor = can_shop & (self.gold >= 12.0) & (self.armor_pts < 13.0)
        self.gold -= np.where(bought_armor, 12.0, 0.0)
        self.armor_pts = np.where(bought_armor, 14.0, self.armor_pts)
        rewards += np.where(bought_armor, 0.45, 0.0)
        bought_fb = can_shop & (self.iron >= 40.0) & (self.fireballs < 2.0)
        self.iron -= np.where(bought_fb, 40.0, 0.0)
        self.fireballs += np.where(bought_fb, 1.0, 0.0)
        bought_up = can_shop & (maneuvers == 2) & (self.diamonds >= 2.0)
        self.diamonds -= np.where(bought_up, 2.0, 0.0)
        self.armor_pts = np.where(bought_up, np.minimum(18.0, self.armor_pts + 1.5), self.armor_pts)
        rewards += np.where(bought_up, 0.35, 0.0)

        # 3: DEFEND_HOME_BED
        m3 = maneuvers == 3
        self.dist_home = np.where(m3, np.maximum(2.0, self.dist_home - 4.0), self.dist_home)
        defending = m3 & (self.dist_home <= 10.0) & (self.wool >= 4.0) & (self.home_def_ratio < 1.0)
        prev_def = self.home_def_ratio.copy()
        self.wool -= np.where(defending, 4.0, 0.0)
        self.home_def_ratio = np.where(defending, np.minimum(1.0, self.home_def_ratio + 0.34), self.home_def_ratio)
        rewards += np.where(defending, 0.55 * (self.home_def_ratio - prev_def), 0.0)

        # 4 & 5: RUSH_NEAREST_BED / RUSH_WEAKEST_BED
        m_rush = (maneuvers == 4) | (maneuvers == 5)
        bridge_adv = np.where(m_rush, 3.0 + 3.2 * speed, 0.0)
        self.dist_enemy_bed = np.maximum(0.0, self.dist_enemy_bed - bridge_adv)
        self.dist_home = np.where(m_rush, np.minimum(90.0, self.dist_home + bridge_adv * 0.85), self.dist_home)
        self.wool = np.where(m_rush, np.maximum(0.0, self.wool - 1.2), self.wool)

        # Void slip hazard when high speed & low caution on bridge without enough caution
        slip_prob = np.where(
            m_rush & (self.dist_home > 14.0) & (self.dist_enemy_bed > 8.0),
            0.008 * np.maximum(0.0, speed - 0.5 * caution) + 0.004 * risk,
            0.0,
        )
        slipped = self.rng.random(n) < slip_prob

        # Arrived at enemy bed -> break bed (weakest breaks significantly faster!)
        break_sec = np.where(maneuvers == 5, self.weakest_bed_sec, self.nearest_bed_sec)
        break_prob = np.where(m_rush & (self.dist_enemy_bed <= 3.5), np.clip(1.3 / np.maximum(0.4, break_sec), 0.2, 0.95), 0.0)
        broke_bed = (self.rng.random(n) < break_prob) & (self.enemy_beds > 0)
        self.enemy_beds = np.where(broke_bed, self.enemy_beds - 1, self.enemy_beds)
        self.dist_enemy_bed = np.where(broke_bed & (self.enemy_beds > 0), self.rng.uniform(32.0, 56.0, size=n), self.dist_enemy_bed)
        self.weakest_bed_sec = np.where(broke_bed, self.rng.uniform(1.0, 3.6, size=n), self.weakest_bed_sec)
        self.nearest_bed_sec = np.where(broke_bed, self.rng.uniform(1.5, 5.0, size=n), self.nearest_bed_sec)
        rewards += np.where(broke_bed, 5.5, 0.0)

        # 6: FARM_DIAMONDS
        m6 = maneuvers == 6
        self.dist_dia = np.where(m6, np.maximum(1.5, self.dist_dia - (2.8 + 2.5 * speed)), np.minimum(45.0, self.dist_dia + 0.5))
        self.dist_home = np.where(m6 & (self.dist_dia > 2.5), np.minimum(60.0, self.dist_home + 2.5), self.dist_home)
        got_dia = m6 & (self.dist_dia <= 3.0)
        self.diamonds += np.where(got_dia, 1.0, 0.0)
        rewards += np.where(got_dia, 0.22, 0.0)

        # 7: FARM_EMERALDS
        m7 = maneuvers == 7
        self.dist_em = np.where(m7, np.maximum(1.5, self.dist_em - (2.8 + 2.5 * speed)), np.minimum(65.0, self.dist_em + 0.5))
        self.dist_home = np.where(m7 & (self.dist_em > 2.5), np.minimum(80.0, self.dist_home + 2.5), self.dist_home)
        got_em = m7 & (self.dist_em <= 3.0)
        self.emeralds += np.where(got_em, 1.0, 0.0)
        rewards += np.where(got_em, 0.35, 0.0)

        # 8: STASH_VALUABLES
        m8 = maneuvers == 8
        self.dist_home = np.where(m8, np.maximum(3.0, self.dist_home - 4.0), self.dist_home)
        stashed = m8 & (self.dist_home <= 8.0) & ((self.diamonds >= 2) | (self.emeralds >= 2))
        rewards += np.where(stashed, 0.25, 0.0)

        # 9: ENGAGE_THREAT_COMBAT
        m9 = maneuvers == 9
        duel = m9 & self.threat_present
        # CombatMode boosts outcome (VOID_KNOCKBACK=1 good when on bridge, CRIT_DUEL=2 good in sword fights, DEFENSIVE_KITE=3 saves hp)
        win_edge = 0.50 + 0.06 * (self.sword_tier - 1.0) + 0.025 * (self.armor_pts - 7.0) + 0.06 * aggr
        win_edge += np.where((combat_modes == 1) & (self.dist_home > 14.0), 0.14, 0.0)
        win_edge += np.where((combat_modes == 2) & (self.dist_home <= 14.0), 0.12, 0.0)
        win_edge += np.where((combat_modes == 3) & (self.health < 11.0), 0.15, 0.0)
        dmg_dealt = np.where(duel, win_edge * 6.5, 0.0)
        self.threat_hp = np.where(duel, self.threat_hp - dmg_dealt, self.threat_hp)
        enemy_won_kill = duel & (self.threat_hp <= 0.0)
        self.threat_present = np.where(enemy_won_kill, False, self.threat_present)
        self.threat_dist = np.where(enemy_won_kill, 48.0, self.threat_dist)
        rewards += np.where(enemy_won_kill, 1.2, 0.0)

        # 10: RETREAT_HOME
        m10 = maneuvers == 10
        self.dist_home = np.where(m10, np.maximum(2.5, self.dist_home - 4.5), self.dist_home)
        self.threat_dist = np.where(m10 & self.threat_present, np.minimum(40.0, self.threat_dist + 2.5), self.threat_dist)

        # 11: FIREBALL_JUMP
        m11 = maneuvers == 11
        used_fb = m11 & (self.fireballs > 0)
        self.fireballs -= np.where(used_fb, 1.0, 0.0)
        self.dist_enemy_bed = np.where(used_fb, np.maximum(0.0, self.dist_enemy_bed - 12.0), self.dist_enemy_bed)
        self.health = np.where(used_fb, np.maximum(2.0, self.health - 2.0), self.health)

        # 12: TACTICAL_VOID_RECALL
        m12_void = maneuvers == 12
        recalled = m12_void & self.home_bed_alive
        self.dist_home = np.where(recalled, 3.0, self.dist_home)
        self.health = np.where(recalled, 20.0, self.health)
        rewards += np.where(recalled & self.enemy_near_home, 0.85, 0.0)

        # Enemy spawns / raid pressure
        spawn_threat = (~self.threat_present) & (self.rng.random(n) < np.where(self.dist_enemy_bed < 16.0, 0.35, 0.07))
        self.threat_present = np.where(spawn_threat, True, self.threat_present)
        self.threat_dist = np.where(spawn_threat, self.rng.uniform(4.5, 16.0, size=n), self.threat_dist)
        self.threat_hp = np.where(spawn_threat, 20.0, self.threat_hp)
        # Close enemy hits player harder if ignoring combat
        self.threat_dist = np.where(self.threat_present & (~m9) & (~m10), np.maximum(2.0, self.threat_dist - 2.2), self.threat_dist)
        incoming_hit = self.threat_present & (self.threat_dist <= 4.5)
        raw_dmg = np.where(incoming_hit, np.where(m9, 2.3, 4.2), 0.0) * (1.0 - np.clip(self.armor_pts / 34.0, 0.0, 0.55))
        self.health -= raw_dmg
        rewards -= 0.05 * raw_dmg

        # Enemy raid against undefended home bed while we're away
        raid_prob = np.where(self.home_bed_alive & (self.dist_home > 20.0) & (~self.enemy_near_home), 0.015 * (1.15 - self.home_def_ratio), 0.0)
        new_raid = self.rng.random(n) < raid_prob
        self.enemy_near_home = self.enemy_near_home | new_raid
        bed_lost = self.enemy_near_home & self.home_bed_alive & (self.dist_home > 14.0) & (self.rng.random(n) < (0.14 * (1.1 - self.home_def_ratio)))
        self.home_bed_alive = self.home_bed_alive & (~bed_lost)
        self.enemy_near_home = np.where((self.dist_home <= 10.0) | bed_lost, False, self.enemy_near_home)
        rewards -= np.where(bed_lost, 4.0, 0.0)

        # Player death
        died = slipped | (self.health <= 0.0)
        rewards -= np.where(died, 3.5, 0.0)
        # Respawn if home bed still alive
        respawned = died & self.home_bed_alive
        self.health = np.where(respawned, 20.0, self.health)
        self.iron = np.where(respawned, 0.0, self.iron)
        self.gold = np.where(respawned, 0.0, self.gold)
        self.diamonds = np.where(respawned, 0.0, self.diamonds)
        self.emeralds = np.where(respawned, 0.0, self.emeralds)
        self.dist_home = np.where(respawned, 3.0, self.dist_home)
        self.dist_enemy_bed = np.where(respawned, 42.0, self.dist_enemy_bed)
        self.threat_present = np.where(respawned, False, self.threat_present)

        won = self.enemy_beds <= 0
        final_eliminated = died & (~self.home_bed_alive)
        timeout = self.step_idx >= self.max_steps
        rewards += np.where(won, 8.0, 0.0)
        done = won | final_eliminated | timeout

        self._reset_mask(done)
        next_obs, next_mask = self._build_obs()
        return next_obs, next_mask, rewards, done


def load_jsonl_bc_dataset(glob_pattern: str) -> Optional[Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]]:
    paths = sorted(glob.glob(glob_pattern))
    if not paths:
        return None
    obs_list, mask_list, m_list, s_list, c_list = [], [], [], [], []
    for p in paths:
        with open(p, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                row = json.loads(line)
                if row.get("schema") != SCHEMA_VERSION or "obs" not in row:
                    continue
                obs = row["obs"]
                if len(obs) != OBS_DIM:
                    continue
                mask = row.get("mask", [1] * NUM_MANEUVERS)
                m = int(row.get("teacherManeuver", row.get("maneuver", 0)))
                c = int(row.get("combatMode", 0))
                sl = row.get("sliders", [0.55, 0.72, 0.45, 0.55])
                obs_list.append(obs)
                mask_list.append([bool(v) for v in mask])
                m_list.append(m)
                s_list.append(sl)
                c_list.append(c)
    if not obs_list:
        return None
    return (
        np.asarray(obs_list, dtype=np.float32),
        np.asarray(mask_list, dtype=bool),
        np.asarray(m_list, dtype=np.int64),
        np.asarray(s_list, dtype=np.float32),
        np.asarray(c_list, dtype=np.int64),
    )


def synthetic_teacher_batch(obs: np.ndarray, mask: np.ndarray) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Fast vectorized teacher labels mirroring RuleTeacherPolicy for BC warm-start."""
    n = obs.shape[0]
    m = np.zeros(n, dtype=np.int64)
    sliders = np.tile(np.array([0.55, 0.72, 0.45, 0.55], dtype=np.float32), (n, 1))
    combat = np.zeros(n, dtype=np.int64)

    hp = obs[:, 0] * 20.0
    iron = obs[:, 14] * 64.0
    gold = obs[:, 15] * 32.0
    dia = obs[:, 16] * 16.0
    em = obs[:, 17] * 16.0
    wool = obs[:, 18] * 64.0
    armor = obs[:, 24] * 20.0
    home_alive = obs[:, 30] > 0.5
    home_def = obs[:, 31]
    dist_home = obs[:, 32] * 128.0
    threat_present = obs[:, 48] > 0.5
    threat_dist = obs[:, 49] * 64.0
    weakest_sec = obs[:, 41] * 15.0
    nearest_sec = obs[:, 40] * 15.0

    for i in range(n):
        if threat_present[i] and threat_dist[i] <= 7.5:
            m[i] = 10 if (hp[i] < 7.0 and dist_home[i] > 14.0 and mask[i, 10]) else 9
        elif dist_home[i] <= 18.0:
            if dia[i] >= 2.0 and mask[i, 2]:
                m[i] = 2
            elif (gold[i] >= 7.0 or armor[i] < 10.0) and mask[i, 2]:
                m[i] = 2
            elif wool[i] < 16.0 and mask[i, 1]:
                m[i] = 1
            elif home_alive[i] and home_def[i] < 0.65 and wool[i] >= 8.0 and mask[i, 3]:
                m[i] = 3
            elif weakest_sec[i] + 0.8 < nearest_sec[i] and mask[i, 5]:
                m[i] = 5
            elif mask[i, 4]:
                m[i] = 4
            else:
                m[i] = 0
        else:
            if hp[i] < 8.5 and mask[i, 10]:
                m[i] = 10
            elif weakest_sec[i] + 0.8 < nearest_sec[i] and mask[i, 5]:
                m[i] = 5
            elif mask[i, 4]:
                m[i] = 4
            elif mask[i, 6]:
                m[i] = 6
            else:
                m[i] = 0
        if not mask[i, m[i]]:
            valid = np.where(mask[i])[0]
            m[i] = int(valid[0]) if len(valid) > 0 else 0
        combat[i] = 3 if hp[i] < 11.0 else (1 if dist_home[i] > 14.0 else 2)
    return m, sliders, combat


def run_bc_pretrain(
    model: PathadolBrainNet,
    device: torch.device,
    data_glob: str,
    bc_epochs: int = 8,
    batch_size: int = 256,
) -> None:
    dataset = load_jsonl_bc_dataset(data_glob)
    if dataset is not None:
        obs_np, mask_np, m_np, s_np, c_np = dataset
        print(f"[BC] Loaded {len(obs_np)} recorded live steps from '{data_glob}'")
    else:
        print(f"[BC] No files matching '{data_glob}' — synthesizing clean teacher demonstrations from Gym")
        gym = VectorizedBedwarsGym(num_envs=128, max_steps=160, seed=7)
        obs_buf, mask_buf, m_buf, s_buf, c_buf = [], [], [], [], []
        o, mk = gym.reset_all()
        for _ in range(96):
            tm, ts, tc = synthetic_teacher_batch(o, mk)
            obs_buf.append(o)
            mask_buf.append(mk)
            m_buf.append(tm)
            s_buf.append(ts)
            c_buf.append(tc)
            o, mk, _, _ = gym.step(tm, ts, tc)
        obs_np = np.concatenate(obs_buf, axis=0)
        mask_np = np.concatenate(mask_buf, axis=0)
        m_np = np.concatenate(m_buf, axis=0)
        s_np = np.concatenate(s_buf, axis=0)
        c_np = np.concatenate(c_buf, axis=0)

    mean = torch.from_numpy(obs_np.mean(axis=0)).to(device)
    std = torch.clamp(torch.from_numpy(obs_np.std(axis=0)), min=0.05).to(device)
    model.obs_mean.copy_(mean)
    model.obs_std.copy_(std)

    opt = torch.optim.AdamW(model.parameters(), lr=1e-3, weight_decay=1e-4)
    n = len(obs_np)
    for ep in range(1, bc_epochs + 1):
        perm = np.random.permutation(n)
        total_loss = 0.0
        batches = 0
        for start in range(0, n, batch_size):
            idx = perm[start : start + batch_size]
            xb = torch.from_numpy(obs_np[idx]).to(device)
            mkb = torch.from_numpy(mask_np[idx]).to(device)
            mb = torch.from_numpy(m_np[idx]).to(device)
            sb = torch.from_numpy(s_np[idx]).to(device)
            cb = torch.from_numpy(c_np[idx]).to(device)
            hb = model.init_hidden(len(idx), device)

            ml, sl, cl, _, _ = model.forward_step(xb, hb)
            ml = ml.masked_fill(~mkb, -1e9)
            loss = F.cross_entropy(ml, mb) + 0.5 * F.cross_entropy(cl, cb) + 0.25 * F.mse_loss(sl, sb)

            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            total_loss += float(loss.item())
            batches += 1
        print(f"[BC] Epoch {ep}/{bc_epochs} loss={total_loss / max(1, batches):.4f}")


def run_ppo_training(
    model: PathadolBrainNet,
    device: torch.device,
    num_envs: int = 256,
    rollout_steps: int = 64,
    total_updates: int = 60,
    lr: float = 3e-4,
    gamma: float = 0.99,
    gae_lambda: float = 0.95,
    clip_eps: float = 0.2,
) -> None:
    gym = VectorizedBedwarsGym(num_envs=num_envs, max_steps=260, seed=101)
    obs_np, mask_np = gym.reset_all()
    h = model.init_hidden(num_envs, device)
    opt = torch.optim.AdamW(model.parameters(), lr=lr, eps=1e-5)

    for upd in range(1, total_updates + 1):
        t0 = time.time()
        b_obs = torch.zeros(rollout_steps, num_envs, OBS_DIM, device=device)
        b_mask = torch.zeros(rollout_steps, num_envs, NUM_MANEUVERS, dtype=torch.bool, device=device)
        b_h = torch.zeros(rollout_steps, num_envs, model.hidden_dim, device=device)
        b_m = torch.zeros(rollout_steps, num_envs, dtype=torch.long, device=device)
        b_c = torch.zeros(rollout_steps, num_envs, dtype=torch.long, device=device)
        b_logp = torch.zeros(rollout_steps, num_envs, device=device)
        b_rew = torch.zeros(rollout_steps, num_envs, device=device)
        b_done = torch.zeros(rollout_steps, num_envs, device=device)
        b_val = torch.zeros(rollout_steps, num_envs, device=device)

        ep_rew_sum = 0.0
        for t in range(rollout_steps):
            t_obs = torch.from_numpy(obs_np).to(device)
            t_mk = torch.from_numpy(mask_np).to(device)
            b_obs[t] = t_obs
            b_mask[t] = t_mk
            b_h[t] = h

            with torch.no_grad():
                ml, sl, cl, val, h_next = model.forward_step(t_obs, h)
                ml = ml.masked_fill(~t_mk, -1e9)
                m_dist = torch.distributions.Categorical(logits=ml)
                c_dist = torch.distributions.Categorical(logits=cl)
                m_act = m_dist.sample()
                c_act = c_dist.sample()
                logp = m_dist.log_prob(m_act) + 0.35 * c_dist.log_prob(c_act)

            next_obs, next_mk, rew_np, done_np = gym.step(
                m_act.cpu().numpy(),
                sl.cpu().numpy(),
                c_act.cpu().numpy(),
            )
            ep_rew_sum += float(rew_np.mean())
            b_m[t] = m_act
            b_c[t] = c_act
            b_logp[t] = logp
            b_rew[t] = torch.from_numpy(rew_np).to(device)
            b_done[t] = torch.from_numpy(done_np.astype(np.float32)).to(device)
            b_val[t] = val

            done_t = torch.from_numpy(done_np).to(device).unsqueeze(-1)
            h = torch.where(done_t, torch.zeros_like(h_next), h_next)
            obs_np, mask_np = next_obs, next_mk

        with torch.no_grad():
            _, _, _, next_val, _ = model.forward_step(torch.from_numpy(obs_np).to(device), h)
            adv = torch.zeros_like(b_rew)
            lastgaelam = torch.zeros(num_envs, device=device)
            for t in reversed(range(rollout_steps)):
                nonterminal = 1.0 - b_done[t]
                nextvalues = next_val if t == rollout_steps - 1 else b_val[t + 1]
                delta = b_rew[t] + gamma * nextvalues * nonterminal - b_val[t]
                lastgaelam = delta + gamma * gae_lambda * nonterminal * lastgaelam
                adv[t] = lastgaelam
            ret = adv + b_val

        # Flatten rollout transitions for clipped PPO updates
        f_obs = b_obs.reshape(-1, OBS_DIM)
        f_mk = b_mask.reshape(-1, NUM_MANEUVERS)
        f_h = b_h.reshape(-1, model.hidden_dim)
        f_m = b_m.reshape(-1)
        f_c = b_c.reshape(-1)
        f_old_logp = b_logp.reshape(-1)
        f_adv = (adv.reshape(-1) - adv.mean()) / (adv.std() + 1e-8)
        f_ret = ret.reshape(-1)

        total_n = f_obs.shape[0]
        mb_size = min(2048, total_n)
        for _ in range(3):
            perm = torch.randperm(total_n, device=device)
            for start in range(0, total_n, mb_size):
                mb = perm[start : start + mb_size]
                ml, _, cl, newval, _ = model.forward_step(f_obs[mb], f_h[mb])
                ml = ml.masked_fill(~f_mk[mb], -1e9)
                m_dist = torch.distributions.Categorical(logits=ml)
                c_dist = torch.distributions.Categorical(logits=cl)
                new_logp = m_dist.log_prob(f_m[mb]) + 0.35 * c_dist.log_prob(f_c[mb])
                ratio = torch.exp(new_logp - f_old_logp[mb])
                surr1 = ratio * f_adv[mb]
                surr2 = torch.clamp(ratio, 1.0 - clip_eps, 1.0 + clip_eps) * f_adv[mb]
                pg_loss = -torch.min(surr1, surr2).mean()
                v_loss = 0.5 * F.mse_loss(newval, f_ret[mb])
                ent = (m_dist.entropy() + 0.25 * c_dist.entropy()).mean()
                loss = pg_loss + 0.5 * v_loss - 0.015 * ent

                opt.zero_grad()
                loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), 0.5)
                opt.step()

        dt = time.time() - t0
        if upd == 1 or upd % 10 == 0 or upd == total_updates:
            sps = (rollout_steps * num_envs) / max(1e-4, dt)
            print(f"[PPO] Update {upd:3d}/{total_updates} mean_rollout_R={ep_rew_sum:.3f} speed={sps:,.0f} steps/s")


def main() -> None:
    parser = argparse.ArgumentParser(description="Train Pathadol Bedwars+PvP Neuro-Symbolic Brain")
    parser.add_argument("--data-glob", default="./brain_data/*.jsonl")
    parser.add_argument("--out", default="./active.pbrain")
    parser.add_argument("--name", default="pathadol-bw-l4")
    parser.add_argument("--bc-epochs", type=int, default=8)
    parser.add_argument("--ppo-updates", type=int, default=60)
    parser.add_argument("--num-envs", type=int, default=256)
    parser.add_argument("--smoke-test", action="store_true", help="Fast 2-update CPU/GPU verification run")
    args = parser.parse_args()

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"[Init] Device={device} (CUDA available={torch.cuda.is_available()})")

    if args.smoke_test:
        args.bc_epochs = 2
        args.ppo_updates = 2
        args.num_envs = 32

    model = PathadolBrainNet(hidden_dim=128, enc_dim=192).to(device)
    run_bc_pretrain(model, device, args.data_glob, bc_epochs=args.bc_epochs)
    run_ppo_training(model, device, num_envs=args.num_envs, total_updates=args.ppo_updates)

    export_pbrain(model, args.out, name=args.name)
    max_err = verify_java_math_parity(model, args.out)
    size_kb = os.path.getsize(args.out) / 1024.0
    print(f"[Export] Wrote '{args.out}' ({size_kb:.1f} KB) — Java math parity max_err={max_err:.2e}")


if __name__ == "__main__":
    main()
