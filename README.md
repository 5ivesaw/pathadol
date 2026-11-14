<p align="center">
  <img src="fabric/src/main/resources/assets/pathadol/icon.png" width="160" alt="Pathadol"/>
</p>

# Pathadol

**Pathadol** is an autonomous, human-like Minecraft **26.3** (Fabric) AI built on top of **[Baritone](https://github.com/cabaletta/baritone)**'s pathfinding and world-cache engine.

Using Baritone as the foundational navigation and block-puzzle layer, Pathadol adds a continuous human motor system, any-angle trajectory smoothing, multi-style fast bridging, dual-era combat, survival reflexes, an autonomous Any% speedrunner, cross-task player style learning, a cinematic Diorama Camera, and a 60-stage Bot-vs-Player Proving Ground.

---

## Key Capabilities

### Human Motor & Perception Layer
- **Fitts' Law Mouse Engine ([`MouseModel`](src/main/java/baritone/human/MouseModel.java)):** Minimum-jerk velocity curves, WindMouse lateral curvature, fast-flick overshoot with corrective sub-movements, continuous 3-stage pink-noise (`1/f`) wrist drift with anchored subpixel accumulation, and exact Minecraft mouse-sensitivity GCD quantization.
- **Omnidirectional Strafe & Key Hysteresis ([`KeyboardModel`](src/main/java/baritone/human/KeyboardModel.java)):** Resolves continuous movement headings into W/A/S/D relative to current camera yaw with hold hysteresis and sprint-cone gating (`humanSprintConeDeg`).
- **Natural Click & Tool Timing ([`ClickModel`](src/main/java/baritone/human/ClickModel.java)):** Crosshair arrival gating, `hold` / `jitter` / `butterfly` CPS distributions, post-break left-click over-hold tails, hotbar look-ahead pre-swapping, and GUI slot travel delays.
- **Perceptual Gating ([`PerceptionGate`](src/main/java/baritone/human/PerceptionGate.java)):** FOV cone visibility checks and log-normal visual/motor reaction delays (`humanReactionMedianMs`).

### Any-Angle Pathing & Movement
- **Corridor String-Pulling ([`PathSmoother`](src/main/java/baritone/human/PathSmoother.java)):** Prunes unnecessary grid zigzags using 2D swept-hitbox line-of-sight checks and pure-pursuit lookahead steering (`pathSmoothing`, `steeringLookahead`).
- **Leading Gaze ([`GazeDirector`](src/main/java/baritone/human/GazeDirector.java)):** Aims along upcoming corridor turns during travel and pre-aims upcoming break/place faces within reach.
- **Normal Survival vs. Bedwars Fall Safety ([`FallSafetyPolicy`](src/main/java/baritone/pathing/movement/FallSafetyPolicy.java)):** Strongly avoids taking fall damage in normal Survival (`avoidSurvivalFallDamage`) while keeping an explicit toggle (`bedwarsMode`) for aggressive Bedwars risk-taking.

### Bridging Arsenal ([`BridgeController`](src/main/java/baritone/human/BridgeController.java))
- Configure `humanBridgeStyle` (`auto`, `safe`, `ninja`, `moonwalk`, `breezily`, `godbridge`, `telly`, `jump`) and skill tier (`humanBridgeSkill`: `safe`, `medium`, `sweaty`), plus humanized block-face aiming and pillaring.

### Dual-Era Combat ([`CombatProcess`](src/main/java/baritone/process/CombatProcess.java))
- Supports **1.8-style** CPS/hit-select/W-tap/S-tap sprint resets and **Modern** attack-cooldown timing (`humanCombatMode`: `1.8`, `modern`, `auto`), shrunk-AABB aiming, timed jump crits, shield handling, and cliff/void-aware circle strafing.

### Autonomous Survival & Any% Speedrun ([`SurvivalBehavior`](src/main/java/baritone/behavior/SurvivalBehavior.java), [`SpeedrunProcess`](src/main/java/baritone/process/SpeedrunProcess.java))
- **Survival Reflexes (`allowSurvivalReflexes`):** Auto-eat, auto-armor upgrades, fire extinguish, and water/hay fall clutches.
- **Any% Ender Dragon Pipeline (`#speedrun` / `#dragon`):** Multi-stage survival progression from wood/stone/iron tools and bucket/flint preparation through Nether portal building, Blaze rod & Ender pearl gathering, Eye of Ender stronghold triangulation, and End crystal/dragon combat. Includes a one-click **Random Seed Dragon** launcher in the title-screen **Auto Tester** menu.

### 60-Stage Proving Ground, Style Learner & Diorama Camera
- **60-Stage Mirror & Fast Runner ([`AutoTestRunner`](src/main/java/baritone/human/AutoTestRunner.java), [`PathadolTesterScreen`](src/main/java/baritone/human/PathadolTesterScreen.java)):** Open **Auto Tester** from Minecraft's main menu to create/launch a low-lag Flat tester world and run `1-60` (All 60), `11-60` (Smart 50), or `1-10` (Core 10) in **Mirror Mode** (Bot plays, resets the arena, hands control to you for `YOUR TURN`, and compares metrics) or **Fast Bot-Only** mode.
- **Cross-Task Player Style Learning ([`HumanStyleLearner`](src/main/java/baritone/human/HumanStyleLearner.java)):** Extracts task-invariant motor characteristics (flick Fitts parameters, sprint cone, click rhythm, steering lookahead, tremor) from Mirror turns into `.minecraft/pathadol/human_learned_profile.json` (`humanProfile = learned`).
- **Diorama Camera ([`DioramaCameraController`](src/main/java/baritone/human/DioramaCameraController.java)):** Toggleable `off` / `iso` / `side` / `close` spring-damped third-person camera (`F7` or `#lab cam`) with optional automatic switch during bot turns (`dioramaCameraOnBot`).
- **HumanLab JSONL Recorder ([`HumanLabRecorder`](src/main/java/baritone/human/HumanLabRecorder.java)):** Records per-tick motor telemetry (`#lab start`, `#lab stop`, `#lab stats`).

---

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) (`>= 0.19.5`) for Minecraft **26.3**.
2. Download the latest `pathadol-standalone-fabric-*.jar` from **[Releases](https://github.com/5ivesaw/pathadol/releases)** (or from the newest green **Actions → Build Pathadol** workflow artifact).
3. Place `pathadol-standalone-fabric-*.jar` into `.minecraft/mods/`.
   - Do **not** install Baritone alongside Pathadol; Pathadol replaces it and declares `provides: ["baritone"]` so third-party Baritone API addons continue working.
4. Configuration, cached worlds, learned profiles, and HumanLab telemetry are stored in `.minecraft/pathadol/`.

### Build Artifacts

| File | Purpose |
|---|---|
| `pathadol-standalone-fabric-*.jar` | Standard playable Fabric mod (recommended) |
| `pathadol-api-fabric-*.jar` | API-only JAR for compiling third-party addons |
| `pathadol-unoptimized-fabric-*.jar` | Unoptimized/debug JAR |

---

## Usage & Commands

Commands use the `#` prefix in chat (type `#help` in game for full clickable command help):

### Pathadol Commands
- `#combat [players|mobs|all|stop|mode <1.8|modern|auto>]` — Engage targets or switch combat ruleset.
- `#speedrun [start|status|stop]` (alias `#dragon`) — Start, inspect, or cancel an autonomous Any% Ender Dragon run.
- `#lab mirror [start] [end]` — Run the Bot-then-You Mirror Proving Ground across stages `1..60`.
- `#lab auto [fast|mirror|core10|smart50|stop] [start] [end]` — Flexible Proving Ground test runner.
- `#lab next` / `#lab retry` / `#lab skip` — Advance, retry, or skip an active `YOUR TURN` Mirror stage.
- `#lab cam [off|iso|side|close|bot]` — Control the Diorama follow camera (or press `F7` in game).
- `#lab profile [casual|average|pro|learned|custom]` — View or switch active motor profile.
- `#lab learn [stats|reset]` — Inspect or clear learned cross-task player kinematics.
- `#lab start [tag]` / `#lab stop` / `#lab stats` — Record and summarize HumanLab JSONL telemetry.

### Core Navigation & Mining Commands (via Baritone base)
- `#goto x y z` / `#goto block` — Humanized navigation to coordinates or blocks.
- `#mine <block...>` / `#farm` / `#explore` / `#tunnel` / `#build <schematic>` — Automated gathering, farming, exploration, and building (full reference in [USAGE.md](USAGE.md)).

### Key Settings

Settings can be viewed or changed in chat via `#<setting> <value>` or `#set <setting> <value>`:

| Setting | Default | Options / Description |
|---|---|---|
| `humanMode` | `true` | Master toggle for Pathadol's Human Motor Layer |
| `humanProfile` | `"pro"` | `casual`, `average`, `pro` (`sweaty`), `learned`, `custom` |
| `humanBridgeStyle` | `"auto"` | `auto`, `safe`, `ninja`, `moonwalk`, `breezily`, `godbridge`, `telly`, `jump` |
| `humanBridgeSkill` | `"sweaty"` | `safe`, `medium`, `sweaty` |
| `humanCombatMode` | `"auto"` | `1.8`, `modern`, `auto` |
| `humanClickStyle` | `"butterfly"` | `hold`, `jitter`, `butterfly` |
| `humanCps` | `13.0` | Mean clicks per second for burst clicking |
| `pathSmoothing` | `true` | Enable any-angle corridor smoothing and continuous steering |
| `steeringLookahead` | `2.6` | Pure-pursuit lookahead distance (blocks) |
| `allowSurvivalReflexes` | `true` | MLG water/hay clutches, auto-eat, auto-armor, fire extinguish |
| `bedwarsMode` | `false` | Enables aggressive Bedwars fall heights & traversal risk |
| `avoidSurvivalFallDamage` | `true` | Strongly penalizes fall-damage drops (`> 3` blocks) in Survival |
| `dioramaCameraMode` | `"off"` | `off`, `iso`, `side`, `close` |
| `dioramaCameraOnBot` | `false` | Auto-switch to `iso` Diorama view during Bot control |

---

## Development & Cloud CI

- Targets **Minecraft 26.3**, **Java 25**, and **Fabric**.
- Testing and release builds run automatically on GitHub Actions:
  - [`.github/workflows/run_tests.yml`](.github/workflows/run_tests.yml) runs `./gradlew test`.
  - [`.github/workflows/gradle_build.yml`](.github/workflows/gradle_build.yml) builds the Fabric JARs, uploads artifacts + SHA-1 `checksums.txt`, and updates the rolling `latest` release on `main` (or tagged `v*` releases).
- Compatibility note: Java namespaces remain under `baritone.*` so existing Baritone API consumers stay compatible.

---

## Foundation, Credits & License

- **Built on top of [Baritone](https://github.com/cabaletta/baritone):** Pathadol builds upon Baritone's A* voxel pathfinder, world scanner/cache, and movement primitives created by leijurv, Brady, and the Baritone contributors. Upstream documentation is preserved in [docs/BARITONE_README.md](docs/BARITONE_README.md) and [FEATURES.md](FEATURES.md).
- Licensed under the **GNU LGPL v3.0** (see [LICENSE](LICENSE)); original copyright notices are preserved in all source files.

> Many servers prohibit any form of client automation, regardless of how natural the movement or camera looks. Use Pathadol only in singleplayer or in environments where automation is permitted.
