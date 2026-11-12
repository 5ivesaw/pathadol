# Pathadol Phase Execution Tracker & Implementation Blueprint

Repository: `https://github.com/5ivesaw/pathadol` (Branch: `main`, Minecraft 26.3, Java 25, Fabric)

---

## Completed Phases (P0, P1, P2, P3, P4, P5, P6, P7, P8 — All 9 Phases Implemented)

### Phase 0 (P0) — Fork, Rebrand to Pathadol & Cloud CI (`DONE`)
- **What was done & how:**
  - Forked `cabaletta/baritone` (`26.3`) into `/home/fivesaw/Panadol/pathadol` and pushed to `5ivesaw/pathadol`.
  - Rebranded visible identity to **Pathadol** (`mod_id = pathadol` providing `baritone` for addon compatibility, chat prefix `[Pathadol]`, `.minecraft/pathadol` directory, `mixins.pathadol.json`) while keeping Java package names as `baritone.*`.
  - Configured GitHub Actions (`gradle_build.yml` and `run_tests.yml`) so all compilation, unit testing, and JAR building happen in the cloud.

### Phase 1 (P1) — Human Motor Core (`DONE`)
- **Files created/modified:**
  - `HumanProfile.java`: `casual`, `average`, and `sweaty` presets, log-normal reaction delay sampler (`sampleReactionTicks(rand, sessionTicks)`), and session fatigue scaling.
  - `MotorIntent.java`: Decouples `desiredLook` (`LookPriority.MUST_HIT`, `GUIDE`, `FREE`) from `desiredMoveYaw` (`Float`).
  - `MouseModel.java`:
    - Fitts' law movement duration (`computeFittsTicks`) + minimum-jerk position profile (`10t^3 - 15t^4 + 6t^5`).
    - WindMouse correlated lateral curvature ($W \leftarrow W/\sqrt{3} + \text{rand}\cdot W_0/\sqrt{5}$ modulated by $\sin(\pi \tau)$).
    - Primary overshoot + 1–3 tick corrective sub-movement on large flicks ($>38^\circ$).
    - Inner-box crosshair coasting + 3-stage Paul Kellet pink-noise ($1/f$) hand drift + exact Minecraft sensitivity GCD quantization (`quantizeAngle`).
  - `KeyboardModel.java`:
    - Persistent per-tick key state, 8-octant omnidirectional W/A/S/D solver relative to `currentCameraYaw` with $\pm 3.5^\circ$ deadband hysteresis, `minKeyHoldTicks` transition hysteresis, and `humanSprintConeDeg` sprint gating.
  - `ClickModel.java`:
    - Crosshair-arrival click gating, 1–3 tick post-break LMB over-hold reaction tail (`onBlockBroken()`), and `HOLD` / `JITTER` / `BUTTERFLY` right-click interval sampling.
  - Wired into `LookBehavior.java`, `InputOverrideHandler.java`, `PlayerMovementInput.java`, `BlockBreakHelper.java`, and `BlockPlaceHelper.java`.

### Phase 2 (P2) — Any-Angle Pathing, Corridor String-Pulling & GazeDirector (`DONE`)
- **Files created/modified:**
  - `PathSmoother.java`:
    - Evaluates consecutive flat `MovementTraverse` and `MovementDiagonal` steps with no `toBreak`/`toPlace`.
    - Performs 2D swept-hitbox (`clearanceRadius = 0.38`) line-of-sight pruning (`hasSweptLineOfSight`) checking floor `canWalkOn`, foot/head `fullyPassable`, and hazard exclusion.
    - Computes pure-pursuit continuous `desiredMoveYaw` at `steeringLookahead` blocks ahead and records accepted corridor cells so cutting corners never triggers `UNREACHABLE` or `possiblyOffPath`.
  - `GazeDirector.java`:
    - Aims 5–7 movements ahead at eye height (`y + 1.52`) during locomotion, or pre-aims 1–3 movements ahead when an upcoming step has `toBreak` or `toPlace` targets within reach, clamped to `humanSprintConeDeg`.
  - Wired into `PathExecutor.java` and `Movement.java`.

### Phase 3 (P3) — Block Breaking & Mining Humanization (`DONE`)
- **Files created/modified:**
  - `RotationUtils.java`:
    - Added `calculateHumanBlockAimPoint(Vec3 center, BlockPos pos)` using a deterministic SplitMix64 triangular distribution per `BlockPos` inside the inner 62% of the block so aim never targets exact `(0.5, 0.5, 0.5)` and stays stable across ticks.
  - `Movement.java`:
    - Added shared-edge pre-aiming in `prepared(MovementState)` when breaking adjacent multi-block sequences (e.g. top-to-bottom 1x2 tunnel blocks).
  - `PerceptionGate.java` & `MineProcess.java`:
    - Gates newly discovered ore blocks in `MineProcess.addNearby()` behind a camera FOV cone check ($68^\circ$ half-angle, or $115^\circ$ within 2.6 blocks) + log-normal reaction delay (`sampleReactionTicks`).

### Phase 8 (P8) — HumanLab Telemetry Recorder & Unit Tests (`DONE`)
- **Files created/modified:**
  - `HumanLabRecorder.java` & `LabCommand.java`: `#lab start [tag]`, `#lab stop`, `#lab stats`, `#lab profile [casual|average|sweaty]`.
  - `HumanMotorCoreTest.java`: Unit tests for Fitts' law, minimum-jerk velocity, GCD quantization, omnidirectional strafe decoupling, hysteresis, click gating/over-hold, `PathSmoother` string-pulling, and `GazeDirector` sprint-cone clamping.

---

## Completed Phases — P4, P5, P6, P7 (`DONE`)

### Phase 4 (P4) — Block Placing, Bridging & Pillaring Humanization (`DONE`)
- **Exact files to create/edit:**
  1. `src/main/java/baritone/human/BridgeController.java` (new):
     - Implements humanized bridging state machine controlled by `Baritone.settings().humanBridgeSkill.value` (`"safe"`, `"medium"`, `"sweaty"`):
       - **`safe` (Humanized Sneak Back-Bridge):** Holds `SNEAK`, backs toward the block edge with a natural $12^\circ\text{--}18^\circ$ diagonal yaw offset and $75.5^\circ\text{--}79.5^\circ$ pitch (never static exact center of face), firing `CLICK_RIGHT` via `ClickModel` once `ctx.isLookingAt(goalLook)`.
       - **`medium` (Ninja / Speedbridge):** Releases `SNEAK` for 2–3 ticks while backing across the inner flat surface of the just-placed block (`distFromEdge > 0.22`), then re-engages `SNEAK` as the player approaches the edge (`distFromEdge <= 0.22 ± 0.03` jitter) and clicks the placement face.
       - **`sweaty` (Fast Diagonal / Jitter Speedbridge):** Uses a $38^\circ\text{--}45^\circ$ yaw offset (`MOVE_BACK` + `MOVE_LEFT`/`MOVE_RIGHT`), tighter un-sneak window (`3–4` ticks un-sneaked per block), and burst clicking (`JITTER`/`BUTTERFLY` CPS stream) across the placement window.
  2. `MovementTraverse.java`:
     - Delegate the back-place bridging branch (lines 326–353) to `BridgeController` when `Baritone.settings().humanMode.value` is true.
  3. `MovementPillar.java`:
     - Replace exact $90^\circ$ straight-down pitch and exact center coordinates with a humanized $85.5^\circ\text{--}88.8^\circ$ pitch and slight yaw/timing variance near jump apex.

### Phase 5 (P5) — CombatProcess (`#combat`) (`DONE`)
- **Exact files to create/edit:**
  1. `src/main/java/baritone/api/process/ICombatProcess.java` (new API interface):
     - Methods: `attack(Predicate<Entity> filter)`, `attackNearest(double range)`, `cancel()`.
  2. `src/main/java/baritone/process/CombatProcess.java` (new process registered in `Baritone.java`):
     - **Target Selection:** Scores nearby `LivingEntity` / `Player` targets by distance, angle, and line-of-sight via `PerceptionGate`.
     - **Aiming (Shrunk-AABB Pursuit):** Computes closest point on target's `getBoundingBox().deflate(0.15)` to player's eye position (clamped to chest/neck height bias), fed into `LookBehavior` (`MouseModel` smooth pursuit with lag).
     - **Dual Combat Ruleset (`Baritone.settings().humanCombatMode.value`):**
       - `"1.8"`: Continuous CPS click stream (`ClickModel` jitter/butterfly at `humanCps`), starts swinging at `3.4` blocks approaching range, and performs **W-tap / S-tap sprint reset** (releases `MOVE_FORWARD` for 2 ticks after landing a hit when `target.hurtTime == 9` or attack swing lands).
       - `"modern"` / `"auto"` (defaults to modern cooldown on 26.3 unless `humanCombatMode` is `"1.8"`): Waits for `ctx.player().getAttackStrengthScale(0.0f) >= 0.92f + jitter`, times jump crits when descending (`fallDistance > 0.05f && !onGround()`), and W-taps to maintain knockback spacing (`2.6..3.1` blocks).
     - **Strafing & Void Safety:** Circle-strafes (`MOVE_LEFT` / `MOVE_RIGHT` switching every `14..28` ticks or on hit) while checking `MovementHelper.canWalkOn` 2 blocks in the strafe direction so the bot never strafes off a cliff or void edge.
  3. `src/main/java/baritone/command/defaults/CombatCommand.java` (new command registered in `DefaultCommands.java`):
     - `#combat [players|mobs|all|stop|mode <1.8|modern|auto>]`.

### Phase 6 (P6) — Inventory, Hotbar Pre-Swapping & GUI Fitts Delay (`DONE`)
- **Exact files to edit:**
  1. `InventoryBehavior.java`:
     - Add **look-ahead hotbar pre-swapping**: inspects the next 2–3 movements on `PathExecutor`; if an upcoming movement requires a pickaxe/tool or throwaway block, selects the hotbar slot `4..7` ticks early.
     - Add **minimum 2-tick finger delay** (`ticksSinceLastSlotChange`) after any hotbar slot change before allowing `InputOverrideHandler` to fire `CLICK_LEFT` or `CLICK_RIGHT` (eliminating 0-tick swap+click flags).
     - Add Fitts' law slot-to-slot cursor travel delay (`3..6` ticks based on slot Euclidean distance) before executing `windowClick` in inventory/container screens.

### Phase 7 (P7) — Behavioral Texture (Anti-Freeze & Micro-Hesitations) (`DONE`)
- **Exact files to edit:**
  1. `PathingBehavior.java`:
     - When `current == null && inProgress != null && Baritone.settings().humanMode.value`: instead of freezing completely still while A* computes the next segment, gently coast forward for up to 8 ticks if the block ahead in the goal direction is safe (`canWalkOn` floor and `fullyPassable` foot/head).
  2. `MovementParkour.java` & `MovementFall.java`:
     - Optional micro-hesitation (1–3 ticks based on `humanMistakeRate`) before risky long parkour jumps or deep falls (>4 blocks).
