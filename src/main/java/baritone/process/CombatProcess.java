/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.ICombatProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.bedwars.BedwarsArenaMemory;
import baritone.bedwars.BedwarsMapCore;
import baritone.bedwars.BedwarsMapCore.BedPOI;
import baritone.bedwars.BedwarsProcess;
import baritone.bedwars.BedwarsTacticalHelper;
import baritone.bedwars.BedwarsTacticalHelper.ClutchCandidate;
import baritone.bedwars.BedwarsTacticalHelper.FireballThreat;
import baritone.behavior.look.ForkableRandom;
import baritone.brain.BrainActionSpace.CombatMode;
import baritone.human.CraftingHelper;
import baritone.human.HumanProfile;
import baritone.human.MouseModel;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.InputOverrideHandler;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Humanized combat engine supporting both 1.8 CPS/W-tap PvP and modern 26.3 cooldown/crit combat.
 */
public final class CombatProcess extends BaritoneProcessHelper implements ICombatProcess {

    public enum CombatRuleset {
        CLASSIC_1_8,
        MODERN_COOLDOWN;

        public static CombatRuleset fromSetting(String value) {
            if (value != null && "1.8".equals(value.trim())) {
                return CLASSIC_1_8;
            }
            return MODERN_COOLDOWN;
        }

        public static CombatRuleset resolveEffective(String settingValue, float itemAttackDelayTicks) {
            if (settingValue != null) {
                String trimmed = settingValue.trim();
                if ("1.8".equals(trimmed)) {
                    return CLASSIC_1_8;
                }
                if ("modern".equalsIgnoreCase(trimmed)) {
                    return MODERN_COOLDOWN;
                }
            }
            return itemAttackDelayTicks <= 2.2f ? CLASSIC_1_8 : MODERN_COOLDOWN;
        }
    }

    /**
     * Pure snapshot of a combat threat used for unit-testable threat-peeling target selection
     * and void-safe 8-way multi-threat perimeter kiting.
     */
    public record ThreatSnapshot(
            int id,
            double x,
            double z,
            double boxDist,
            double radialClosingSpeed,
            double timeToMeleeTicks,
            float absFlankDeg,
            int hurtTime,
            float health,
            boolean creeperSwelling,
            boolean ranged,
            double tacticalPriorityAdjustment
    ) {
        public ThreatSnapshot(
                int id,
                double x,
                double z,
                double boxDist,
                double radialClosingSpeed,
                double timeToMeleeTicks,
                float absFlankDeg,
                int hurtTime,
                float health,
                boolean creeperSwelling
        ) {
            this(id, x, z, boxDist, radialClosingSpeed, timeToMeleeTicks, absFlankDeg, hurtTime, health, creeperSwelling, false, 0.0);
        }

        public ThreatSnapshot(
                int id,
                double x,
                double z,
                double boxDist,
                double radialClosingSpeed,
                double timeToMeleeTicks,
                float absFlankDeg,
                int hurtTime,
                float health,
                boolean creeperSwelling,
                boolean ranged
        ) {
            this(id, x, z, boxDist, radialClosingSpeed, timeToMeleeTicks, absFlankDeg, hurtTime, health, creeperSwelling, ranged, 0.0);
        }
    }

    /**
     * 16-ray spatial clearance environment around the player: {@code safe}, nearest solid
     * obstacle distance {@code wallDist}, and nearest void/liquid edge distance {@code voidDist}.
     */
    public record RayEnv16(boolean[] safe, double[] wallDist, double[] voidDist) {}

    public enum SpacingAction {
        NONE,
        W_TAP,
        S_TAP
    }

    public enum BridgeWallCounter {
        NONE,
        STEP_JUMP,
        FAST_TOOL_BOTTOM_BREAK,
        HIGH_GROUND_PILLAR
    }

    private static final double ENGAGE_DIRECT_RANGE = 4.35;
    private static final double ENGAGE_CHASE_MAX_RANGE = 12.50;
    private static final double ATTACK_REACH = 2.98;
    private static final double HITBOX_SHRINK_RATIO = 0.16;

    private final ForkableRandom rand;
    private final Map<Integer, Long> targetReadyTick = new HashMap<>();
    private final Map<Integer, Vec3> prevEntityPositions = new HashMap<>();

    private Predicate<Entity> targetFilter;
    private long tickCounter = 0L;
    private long lastCombatTick = -100L;
    private int currentPrimaryId = -1;
    private int primaryLockTicks = 0;
    private transient LivingEntity activeTargetEntity = null;
    private Float prevKiteYaw = null;
    private BlockPos lastPathGoalPos = null;
    private boolean prevCommandWasPath = false;
    private boolean prevGoalExact = false;
    private int strafeDir = 1;
    private int strafeSwitchTicks = 14;
    private int strafeFlipCooldown = 0;
    private int wTapTicks = 0;
    private int sTapTicks = 0;
    private int critHoldTicks = 0;
    private int blockHitTicks = 0;
    private int bowDrawTicks = 0;
    private long lastRodUseTick = -100L;
    private long lastCombatFireballTick = -100L;
    private long lastThrowableTick = -100L;
    private long lastVoidPearlTick = -100L;
    private double clickAccumulator = 0.0;
    private long lastClassicAttackTick = -100L;
    private long nextClassicAttackAllowedTick = 0L;
    private long prevClassicAttackDt = 10L;
    private int modernCooldownWaitJitter = 0;
    private int lastSpacedTargetId = -1;
    private int lastSpacedTargetHurtTime = 0;
    private int lastJumpResetPlayerHurtTime = 0;
    private int hitSelectWaitTicks = 0;
    private boolean hitSelectLogEmitted = false;
    private int shieldAxeDelayTicks = 0;
    private int shieldSwapBackDelayTicks = 0;
    private int shieldReturnSwordSlot = -1;
    private long lastShieldBreakTick = -100L;
    private long lastFireballPunchTick = -100L;
    private long lastBridgeBlockTrapTick = -100L;
    private int bridgeBlockTrapStepTicks = 0;
    private BlockPos activeBridgeTrapFloor = null;
    private int bridgeWallPillarTicks = 0;
    private int swingsRecorded = 0;
    private double attackStrengthSum = 0.0;
    private int combatHitCount = 0;
    private volatile int autoCancelAfterClearTicks = 0;
    private int consecutiveEmptyTicks = 0;
    private volatile CombatMode brainCombatMode = CombatMode.BALANCED;
    private volatile ToDoubleFunction<LivingEntity> contextualPriorityResolver = null;

    /**
     * Sets an optional external priority scorer returning a delta applied during threat selection
     * (negative values prioritize the target; positive values de-prioritize it).
     */
    public void setContextualPriorityResolver(ToDoubleFunction<LivingEntity> resolver) {
        this.contextualPriorityResolver = resolver;
    }

    public CombatProcess(Baritone baritone) {
        super(baritone);
        long seed = Baritone.settings().humanSeed.value;
        this.rand = seed != 0L ? new ForkableRandom(seed ^ 0xA5A5A5A55A5A5A5AL) : new ForkableRandom();
    }

    @Override
    public void attack(Predicate<Entity> filter) {
        this.autoCancelAfterClearTicks = 0;
        this.consecutiveEmptyTicks = 0;
        this.combatHitCount = 0;
        this.targetFilter = filter;
        this.targetReadyTick.clear();
        this.prevEntityPositions.clear();
        this.currentPrimaryId = -1;
        this.primaryLockTicks = 0;
        this.activeTargetEntity = null;
        this.prevKiteYaw = null;
        this.lastPathGoalPos = null;
        this.prevCommandWasPath = false;
        this.prevGoalExact = false;
        this.lastCombatTick = -100L;
        this.strafeFlipCooldown = 0;
        this.wTapTicks = 0;
        this.sTapTicks = 0;
        this.critHoldTicks = 0;
        this.blockHitTicks = 0;
        this.bowDrawTicks = 0;
        this.lastRodUseTick = -100L;
        this.clickAccumulator = 0.0;
        this.lastClassicAttackTick = -100L;
        this.prevClassicAttackDt = 10L;
        this.lastSpacedTargetId = -1;
        this.lastSpacedTargetHurtTime = 0;
        this.lastJumpResetPlayerHurtTime = 0;
        this.hitSelectWaitTicks = 0;
        this.hitSelectLogEmitted = false;
        this.shieldAxeDelayTicks = 0;
        this.shieldSwapBackDelayTicks = 0;
        this.shieldReturnSwordSlot = -1;
    }

    /**
     * Engages targets temporarily (e.g. during manual {@code #bws rush} bridge interception) and
     * automatically cancels after {@code emptyCancelTicks} consecutive threat-free ticks so
     * lower-priority Bedwars navigation resumes seamlessly.
     */
    public void attackTemporary(Predicate<Entity> filter, int emptyCancelTicks) {
        attack(filter);
        this.autoCancelAfterClearTicks = Math.max(1, emptyCancelTicks);
    }

    @Override
    public void cancel() {
        this.targetFilter = null;
        this.autoCancelAfterClearTicks = 0;
        this.consecutiveEmptyTicks = 0;
        this.combatHitCount = 0;
        this.targetReadyTick.clear();
        this.prevEntityPositions.clear();
        this.currentPrimaryId = -1;
        this.primaryLockTicks = 0;
        this.activeTargetEntity = null;
        this.prevKiteYaw = null;
        this.lastPathGoalPos = null;
        this.prevCommandWasPath = false;
        this.prevGoalExact = false;
        this.lastCombatTick = -100L;
        this.strafeFlipCooldown = 0;
        this.wTapTicks = 0;
        this.sTapTicks = 0;
        this.critHoldTicks = 0;
        this.blockHitTicks = 0;
        this.bowDrawTicks = 0;
        this.lastRodUseTick = -100L;
        this.clickAccumulator = 0.0;
        this.lastClassicAttackTick = -100L;
        this.prevClassicAttackDt = 10L;
        this.lastSpacedTargetId = -1;
        this.lastSpacedTargetHurtTime = 0;
        this.lastJumpResetPlayerHurtTime = 0;
        this.hitSelectWaitTicks = 0;
        this.hitSelectLogEmitted = false;
        this.shieldAxeDelayTicks = 0;
        this.shieldSwapBackDelayTicks = 0;
        this.shieldReturnSwordSlot = -1;
        if (baritone.getBedwarsProcess() != null) {
            baritone.getBedwarsProcess().resetVoidClutchState();
        }
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    /**
     * Returns the current primary combat target entity, or {@code null} when idle.
     */
    public LivingEntity getActiveTargetEntity() {
        LivingEntity cur = this.activeTargetEntity;
        if (cur != null && cur.isAlive() && !cur.isRemoved()) {
            return cur;
        }
        return null;
    }

    @Override
    public boolean isActive() {
        return targetFilter != null;
    }

    @Override
    public void onLostControl() {
        cancel();
    }

    @Override
    public double priority() {
        return 4.0;
    }

    @Override
    public String displayName0() {
        return "Combat (" + Baritone.settings().humanCombatMode.value + ")";
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        tickCounter++;
        if (ctx.player() == null || ctx.world() == null || targetFilter == null) {
            return null;
        }
        Minecraft mcCheck = ctx.minecraft();
        if (mcCheck != null && ((mcCheck.gui != null && mcCheck.gui.screen() != null) || mcCheck.isPaused())) {
            if (Baritone.settings().bedwarsMode.value
                    && mcCheck.gui.screen() instanceof AbstractContainerScreen<?>
                    && ctx.player() != null) {
                CraftingHelper.safeCloseContainer(ctx);
            } else {
                baritone.getInputOverrideHandler().clearAllKeys();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        }
        if (Baritone.settings().bedwarsMode.value
                && baritone.getBedwarsArenaMemory() != null
                && baritone.getBedwarsArenaMemory().isInWaitingLobby()) {
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (baritone.getLookBehavior() != null) {
            baritone.getLookBehavior().setBridgeAnchorMode(false);
        }
        if (baritone.getSurvivalBehavior() != null && baritone.getSurvivalBehavior().isUsingSurvivalReflex()) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, false);
            // While pre-gapping at safe distance, back-kite smoothly on solid ground instead of standing still
            if (activeTargetEntity != null && activeTargetEntity.isAlive() && ctx.player() != null) {
                Rotation toThreat = RotationUtils.calcRotationFromVec3d(
                        ctx.playerHead(), activeTargetEntity.getBoundingBox().getCenter(), ctx.playerRotations());
                RayEnv16 gapEnv = probeEnvironment16(toThreat.getYaw());
                baritone.getInputOverrideHandler().clearMovementKeys();
                baritone.getLookBehavior().updateTarget(toThreat, false, true);
                if (ctx.player().distanceTo(activeTargetEntity) <= 12.0 && gapEnv.safe()[8] && gapEnv.voidDist()[8] >= 2.6) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                }
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (tickCounter > 0 && shieldSwapBackDelayTicks > 0) {
            shieldSwapBackDelayTicks--;
            if (shieldSwapBackDelayTicks == 0 && shieldReturnSwordSlot >= 0
                    && baritone.getInventoryBehavior().isSlotSwitchSafe()) {
                baritone.getInventoryBehavior().safeSelectHotbarSlot(shieldReturnSwordSlot);
                shieldReturnSwordSlot = -1;
            }
        }
        if (shieldAxeDelayTicks > 0) {
            shieldAxeDelayTicks--;
        }

        if (Baritone.settings().bedwarsMode.value && tryReactiveVoidClutch()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (Baritone.settings().bedwarsMode.value && tryVoidSaveEnderPearl()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        List<LivingEntity> candidates = new ArrayList<>();
        List<ThreatSnapshot> threats = new ArrayList<>();
        collectTargets(candidates, threats);
        if (Baritone.settings().bedwarsMode.value && tryAntiFireballDeflect(candidates)) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (threats.isEmpty()) {
            this.currentPrimaryId = -1;
            this.primaryLockTicks = 0;
            this.activeTargetEntity = null;
            this.hitSelectWaitTicks = 0;
            this.hitSelectLogEmitted = false;
            this.prevKiteYaw = null;
            this.lastPathGoalPos = null;
            this.prevCommandWasPath = false;
            this.prevGoalExact = false;
            baritone.getInputOverrideHandler().clearAllKeys();
            if (autoCancelAfterClearTicks > 0 && ++consecutiveEmptyTicks >= autoCancelAfterClearTicks) {
                cancel();
                return null;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        consecutiveEmptyTicks = 0;

        int rawPrimaryIdx = selectPrimaryThreatIndex(threats, currentPrimaryId);
        int primaryIdx = rawPrimaryIdx;
        if (primaryLockTicks > 0) {
            primaryLockTicks--;
        }
        // Prevent 2v1 camera whipping every 5 ticks: hold primary focus briefly unless current target left/died
        // or a new unhurt charger is in immediate melee ambush range (< 3.1m).
        if (primaryLockTicks > 0 && currentPrimaryId >= 0 && threats.get(rawPrimaryIdx).id() != currentPrimaryId) {
            int curIdx = -1;
            for (int i = 0; i < threats.size(); i++) {
                if (threats.get(i).id() == currentPrimaryId) {
                    curIdx = i;
                    break;
                }
            }
            ThreatSnapshot candThreat = threats.get(rawPrimaryIdx);
            boolean immediateAmbush = candThreat.hurtTime() <= 2 && candThreat.boxDist() <= 3.1 && candThreat.timeToMeleeTicks() <= 5.0;
            if (curIdx >= 0 && threats.get(curIdx).boxDist() <= 4.6 && !immediateAmbush) {
                primaryIdx = curIdx;
            }
        }
        LivingEntity target = candidates.get(primaryIdx);
        ThreatSnapshot primaryThreat = threats.get(primaryIdx);
        if (this.currentPrimaryId != target.getId()) {
            this.hitSelectWaitTicks = 0;
            this.hitSelectLogEmitted = false;
            this.primaryLockTicks = 8;
        }
        this.currentPrimaryId = target.getId();
        this.activeTargetEntity = target;
        HumanProfile profile = HumanProfile.fromSettings();

        // Apply initial perception gate; mid-brawl threat switches react cleanly without freezing
        Long readyAt = targetReadyTick.get(target.getId());
        if (readyAt == null) {
            long delay = (tickCounter - lastCombatTick <= 6L) ? 0L : (long) profile.sampleReactionTicks(rand, tickCounter);
            readyAt = tickCounter + delay;
            targetReadyTick.put(target.getId(), readyAt);
        }

        CombatRuleset ruleset = CombatRuleset.resolveEffective(
                Baritone.settings().humanCombatMode.value,
                ctx.player().getCurrentItemAttackStrengthDelay()
        );
        Vec3 eyePos = computeEffectiveEyePos(ruleset);
        // Track true enemy velocity via position delta (RemotePlayer.getDeltaMovement() is 0 while walking,
        // so subtracting playerVel shifted aim -0.35m off the live hitbox!). Lead modestly and keep aim on-hitbox.
        Vec3 trueEnemyVel = sampleTrueEntityVelocity(target);
        Vec3 aimPoint = computeReachOptimalAimPoint(eyePos, target.getBoundingBox(), trueEnemyVel, 0.35, HITBOX_SHRINK_RATIO);
        double boxDist = distanceToAabb(eyePos, target.getBoundingBox());
        double bodyDist = ctx.player().distanceTo(target);

        Rotation desiredRot = RotationUtils.calcRotationFromVec3d(eyePos, aimPoint, ctx.playerRotations());
        boolean hasLos = hasEffectiveCombatVisibility(eyePos, target);
        RayEnv16 env16 = probeEnvironment16(desiredRot.getYaw());

        if (bodyDist > ENGAGE_DIRECT_RANGE || !hasLos) {
            PathingCommand overheadHold = tryHoldHomeGroundAgainstOverheadEnemy(target, desiredRot, eyePos, bodyDist, hasLos);
            if (overheadHold != null) {
                return overheadHold;
            }
        }

        // Pro BedWars Block-PvP: instantly counter enemy-placed bridge walls (shears bottom-block break in ~150ms
        // or jump-pillar onto the wall for +2Y high ground) instead of oscillating W/S behind wool for seconds!
        if (Baritone.settings().bedwarsMode.value && bodyDist <= 8.5
                && tryCounterBlockingPlayerWall(eyePos, target, desiredRot, bodyDist, env16)) {
            this.prevCommandWasPath = false;
            this.lastPathGoalPos = null;
            this.prevGoalExact = false;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Pro BedWars Bridge Block-Trap: drop a quick block in front of a charging enemy on a narrow bridge
        // to cut their sprint combo and take immediate +1Y high ground.
        if (Baritone.settings().bedwarsMode.value && hasLos
                && tryTacticalBridgeBlockTrap(eyePos, target, primaryThreat, desiredRot, bodyDist, env16)) {
            this.prevCommandWasPath = false;
            this.lastPathGoalPos = null;
            this.prevGoalExact = false;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (!hasLos) {
            boolean closeUnblockedThreat = hasCloseUnblockedThreat(candidates, 8.5);
            boolean enclosed = isPlayerBoxedIn(ctx.playerFeet());
            if (shouldAttemptBarrierBreach(boxDist, bodyDist, closeUnblockedThreat, enclosed)
                    && tryDirectBarrierBreach(eyePos, target, desiredRot.getYaw(), true, bodyDist)) {
                this.prevCommandWasPath = false;
                this.lastPathGoalPos = null;
                this.prevGoalExact = false;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            // Block-PvP / single placed wool: stay focused on the opponent & jump/flank around instead of staring/digging wool
            if (bodyDist <= 6.5 && tryFlankOrStepOverObstacle(eyePos, target, desiredRot, bodyDist, env16)) {
                this.prevCommandWasPath = false;
                this.lastPathGoalPos = null;
                this.prevGoalExact = false;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            prevKiteYaw = null;
            return makeCombatPathCommand(target.blockPosition(), false);
        }

        if (bodyDist > ENGAGE_DIRECT_RANGE) {
            if (hasLos && Baritone.settings().bedwarsMode.value && tryCombatBridgeFireball(target, eyePos, trueEnemyVel, bodyDist)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (hasLos && handleRangedBowFallback(target, eyePos, bodyDist)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (hasLos && Baritone.settings().bedwarsMode.value && tryCombatThrowableOpener(target, desiredRot, bodyDist)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (hasLos && handleFishingRodInterrupt(target, desiredRot, bodyDist)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (bodyDist <= ENGAGE_CHASE_MAX_RANGE && env16.safe()[0] && env16.voidDist()[0] >= 1.95) {
                this.prevCommandWasPath = false;
                this.lastPathGoalPos = null;
                this.prevGoalExact = false;
                // Pre-draw optimal melee weapon (KB stick on bridge/cliff, sword otherwise) before contact!
                if (bodyDist <= 8.5) {
                    WarriorLoadout approachLoadout = scanWarriorLoadout(ctx.player().getInventory());
                    selectOptimalMeleeWeapon(target, boxDist, approachLoadout, env16);
                }
                ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(desiredRot, false, true);
                ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
                baritone.getInputOverrideHandler().clearCombatKeysPreservingHysteresis();
                float chaseYaw = chooseContextKiteYaw16(
                        threats, primaryIdx, ctx.player().position().x, ctx.player().position().z,
                        desiredRot.getYaw(), prevKiteYaw, false, false, strafeDir, env16);
                float moveYaw = Float.isNaN(chaseYaw) ? desiredRot.getYaw() : chaseYaw;
                prevKiteYaw = moveYaw;
                baritone.getInputOverrideHandler().setDesiredMovementYaw(moveYaw);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
                if (bodyDist >= 5.8 && ctx.player().onGround() && env16.safe()[0] && env16.voidDist()[0] >= 3.0
                        && hasCombatJumpCeilingClearance(moveYaw)) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                }
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            bowDrawTicks = 0;
            baritone.getInputOverrideHandler().clearAllKeys();
            prevKiteYaw = null;
            return makeCombatPathCommand(target.blockPosition(), false);
        }

        bowDrawTicks = 0;
        this.prevCommandWasPath = false;
        this.lastPathGoalPos = null;
        this.prevGoalExact = false;
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(desiredRot, false, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
        baritone.getInputOverrideHandler().clearCombatKeysPreservingHysteresis();
        if (tickCounter < readyAt) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        lastCombatTick = tickCounter;

        WarriorLoadout loadout = scanWarriorLoadout(ctx.player().getInventory());

        float cooldown = ctx.player().getAttackStrengthScale(0.5f);
        boolean creeperSwelling = primaryThreat.creeperSwelling();
        boolean openFooting = updateMultiThreatSpacing(target, threats, primaryIdx, desiredRot.getYaw(), boxDist, ruleset, cooldown, env16);

        boolean swarmed = hasCloseSecondaryThreat(threats, primaryIdx, 2.35);
        boolean hasSecondaryThreat = hasCloseSecondaryThreat(threats, primaryIdx, 3.60);
        boolean crosshairAligned = isCrosshairOnTarget(eyePos, target.getBoundingBox(), ctx.playerRotations(), 3.35);
        boolean targetBlocking = target.isBlocking();
        boolean shieldFacingUs = targetBlocking && isShieldBlockingUs(
                ctx.player().position(),
                target.position(),
                target.getViewVector(1.0F)
        );
        boolean shieldBreakActive = ruleset != CombatRuleset.CLASSIC_1_8
                && shieldFacingUs
                && loadout.axeSlot() >= 0
                && boxDist <= ATTACK_REACH + 0.50;
        boolean shielding = ruleset != CombatRuleset.CLASSIC_1_8 && shouldUseShieldDefense(
                true,
                loadout.hasShield(),
                cooldown,
                boxDist,
                targetBlocking,
                primaryThreat.ranged(),
                hasCloseSecondaryThreat(threats, primaryIdx, 3.5),
                creeperSwelling
        );
        if (shieldSwapBackDelayTicks <= 0) {
            if (shieldBreakActive) {
                int held = ctx.player().getInventory().getSelectedSlot();
                if (held != loadout.axeSlot() && baritone.getInventoryBehavior().isSlotSwitchSafe()) {
                    if (loadout.swordSlot() >= 0) {
                        shieldReturnSwordSlot = loadout.swordSlot();
                    }
                    if (baritone.getInventoryBehavior().safeSelectHotbarSlot(loadout.axeSlot())) {
                        shieldAxeDelayTicks = 2;
                    }
                }
            } else if (shielding && !loadout.offhandShield() && loadout.shieldSlot() >= 0) {
                if (baritone.getInventoryBehavior().isSlotSwitchSafe()) {
                    baritone.getInventoryBehavior().safeSelectHotbarSlot(loadout.shieldSlot());
                }
            } else {
                selectOptimalMeleeWeapon(target, boxDist, loadout, env16);
            }
        }

        if (ruleset == CombatRuleset.CLASSIC_1_8) {
            stepClassic18Attack(target, eyePos, boxDist, crosshairAligned, profile, loadout);
        } else {
            if (shielding && !shieldBreakActive) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
            boolean allowJumpCrit = openFooting
                    && !creeperSwelling
                    && !hasSecondaryThreat
                    && !shielding
                    && !shieldBreakActive
                    && env16.voidDist()[0] >= 2.65
                    && env16.voidDist()[8] >= 2.65
                    && brainCombatMode != CombatMode.DEFENSIVE_KITE;
            if (brainCombatMode == CombatMode.CRIT_DUEL && openFooting && !creeperSwelling && !shielding && env16.voidDist()[8] >= 2.45) {
                allowJumpCrit = true;
            }
            stepModernAttack(target, eyePos, boxDist, crosshairAligned, allowJumpCrit, swarmed, shieldBreakActive, loadout);
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    /**
     * Pure tactical gate: when defending a living BedWars home bed and an enemy is bridging high
     * overhead ({@code >= 3.5} blocks above us), hold solid home-island ground instead of pillaring
     * up onto their narrow void sky-bridge where one fireball or knockback hit is lethal.
     */
    public static boolean shouldHoldHomeGroundAgainstOverheadEnemy(
            boolean bedwarsMode,
            boolean homeBedAlive,
            double myDistToHomeBed,
            double enemyHorizDistToHomeBed,
            double enemyDyAboveMe
    ) {
        return bedwarsMode
                && homeBedAlive
                && myDistToHomeBed <= 16.0
                && enemyHorizDistToHomeBed <= 16.0
                && enemyDyAboveMe >= 3.5;
    }

    private PathingCommand tryHoldHomeGroundAgainstOverheadEnemy(
            LivingEntity target,
            Rotation desiredRot,
            Vec3 eyePos,
            double bodyDist,
            boolean hasLos
    ) {
        if (!Baritone.settings().bedwarsMode.value || target == null || ctx.player() == null) {
            return null;
        }
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        BedwarsMapCore core = memory != null ? memory.getActiveCore() : null;
        if (core == null) {
            return null;
        }
        BedPOI homeBed = null;
        for (BedPOI b : core.getBeds()) {
            if (b.isHome() && !b.destroyed()) {
                homeBed = b;
                break;
            }
        }
        if (homeBed == null) {
            return null;
        }
        Vec3 mePos = ctx.player().position();
        double bx = homeBed.x() + 0.5;
        double by = homeBed.y() + 0.5;
        double bz = homeBed.z() + 0.5;
        double myDistToBed = Math.hypot(Math.hypot(mePos.x - bx, mePos.z - bz), mePos.y - by);
        double enemyHorizToBed = Math.hypot(target.getX() - bx, target.getZ() - bz);
        double enemyDy = target.getY() - mePos.y;
        if (!shouldHoldHomeGroundAgainstOverheadEnemy(true, true, myDistToBed, enemyHorizToBed, enemyDy)) {
            return null;
        }
        if (hasLos && handleRangedBowFallback(target, eyePos, bodyDist)) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        BlockPos bedPos = new BlockPos(homeBed.x(), homeBed.y(), homeBed.z());
        if (myDistToBed > 4.8) {
            baritone.getInputOverrideHandler().clearAllKeys();
            prevKiteYaw = null;
            return new PathingCommand(new GoalNear(bedPos, 3), PathingCommandType.SET_GOAL_AND_PATH);
        }
        // Hold solid island footing near our bed, track the overhead rusher, and combo them on drop
        this.prevCommandWasPath = false;
        this.lastPathGoalPos = null;
        this.prevGoalExact = false;
        baritone.getInputOverrideHandler().clearAllKeys();
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(desiredRot, false, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand makeCombatPathCommand(BlockPos targetPos, boolean exactBlock) {
        boolean goalShifted = !prevCommandWasPath
                || prevGoalExact != exactBlock
                || lastPathGoalPos == null
                || lastPathGoalPos.distSqr(targetPos) > 2.25;
        if (goalShifted) {
            lastPathGoalPos = targetPos;
        }
        BlockPos anchor = lastPathGoalPos != null ? lastPathGoalPos : targetPos;
        Goal goal = exactBlock ? new GoalBlock(anchor) : new GoalNear(anchor, 1);
        PathingCommandType type = goalShifted
                ? PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH
                : PathingCommandType.REVALIDATE_GOAL_AND_PATH;
        prevCommandWasPath = true;
        prevGoalExact = exactBlock;
        return new PathingCommand(goal, type);
    }

    private boolean hasCloseUnblockedThreat(List<LivingEntity> candidates, double maxBoxDist) {
        Vec3 eyePos = ctx.playerHead();
        for (LivingEntity e : candidates) {
            if (distanceToAabb(eyePos, e.getBoundingBox()) <= maxBoxDist && hasEffectiveCombatVisibility(eyePos, e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks head, torso, waist, and lateral shoulder rays so a single placed wool block doesn't mask an exposed opponent.
     */
    private boolean hasEffectiveCombatVisibility(Vec3 eyePos, LivingEntity target) {
        if (target == null || ctx.world() == null) {
            return false;
        }
        if (ctx.player() != null && ctx.player().hasLineOfSight(target)) {
            return true;
        }
        AABB box = target.getBoundingBox();
        Vec3 center = box.getCenter();
        double halfW = Math.max(0.15, (box.maxX - box.minX) * 0.35);
        double[] yFracs = {0.85, 0.55, 0.25};
        double[][] xzOffsets = {{0.0, 0.0}, {halfW, 0.0}, {-halfW, 0.0}, {0.0, halfW}, {0.0, -halfW}};
        for (double yf : yFracs) {
            double y = box.minY + (box.maxY - box.minY) * yf;
            for (double[] off : xzOffsets) {
                Vec3 probe = new Vec3(center.x + off[0], y, center.z + off[1]);
                BlockHitResult hit = ctx.world().clip(new ClipContext(eyePos, probe, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player()));
                if (hit == null || hit.getType() == HitResult.Type.MISS) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Only mine a barrier during combat when genuinely sealed in or target is walled off beyond melee/flank range.
     */
    public static boolean shouldAttemptBarrierBreach(
            double boxDist,
            double bodyDist,
            boolean closeUnblockedThreat,
            boolean playerBoxedIn
    ) {
        if (closeUnblockedThreat) {
            return false;
        }
        if (playerBoxedIn) {
            return true;
        }
        // In open block-PvP (< 5.2m), NEVER stop to mine single placed wool — strafe/jump around it
        return bodyDist > 5.2 && boxDist > 4.6;
    }

    private boolean isPlayerBoxedIn(BlockPos feet) {
        if (feet == null || ctx.world() == null) {
            return false;
        }
        int blockedSides = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            BlockPos footAdj = feet.offset(d[0], 0, d[1]);
            BlockPos headAdj = feet.offset(d[0], 1, d[1]);
            boolean lowWall = !ctx.world().getBlockState(footAdj).canBeReplaced();
            boolean highWall = !ctx.world().getBlockState(headAdj).canBeReplaced();
            if (highWall || (lowWall && !ctx.world().getBlockState(feet.above(2)).canBeReplaced())) {
                blockedSides++;
            }
        }
        return blockedSides >= 3;
    }

    /**
     * Pure deterministic counter selector when an enemy-placed block wall directly obstructs our path in BedWars.
     */
    public static BridgeWallCounter chooseBridgeWallCounter(
            boolean hasFastToolForBottom,
            boolean twoHighWall,
            boolean ceilingClear,
            int bridgeBlocks,
            boolean narrowBridge
    ) {
        if (!twoHighWall) {
            return ceilingClear ? BridgeWallCounter.STEP_JUMP : BridgeWallCounter.FAST_TOOL_BOTTOM_BREAK;
        }
        if (hasFastToolForBottom) {
            // Shears vs wool (0.15s = 3 ticks) or proper tool vs wood/endstone beats any flank race!
            return BridgeWallCounter.FAST_TOOL_BOTTOM_BREAK;
        }
        if (ceilingClear && bridgeBlocks >= 3) {
            // Pillar 1 block up & leap onto the enemy's 2-block wall for instant +2Y high-ground crits
            return BridgeWallCounter.HIGH_GROUND_PILLAR;
        }
        return narrowBridge ? BridgeWallCounter.FAST_TOOL_BOTTOM_BREAK : BridgeWallCounter.NONE;
    }

    private boolean tryCounterBlockingPlayerWall(
            Vec3 eyePos,
            LivingEntity target,
            Rotation desiredRot,
            double bodyDist,
            RayEnv16 env16
    ) {
        if (ctx.player() == null || ctx.world() == null || !(baritone instanceof Baritone b)) {
            bridgeWallPillarTicks = 0;
            return false;
        }
        BedwarsArenaMemory mem = b.getBedwarsArenaMemory();
        BedwarsMapCore core = mem != null ? mem.getActiveCore() : null;
        if (core == null) {
            bridgeWallPillarTicks = 0;
            return false;
        }
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        Vec3 pos = ctx.player().position();
        int feetY = ctx.playerFeet().y;
        double yawRad = Math.toRadians(desiredRot.getYaw());
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);

        // Probe 0.85..1.65 blocks directly along our path to target for an enemy-placed obstruction
        BlockPos wallBottom = null;
        BlockPos wallTop = null;
        for (double dist : new double[]{0.85, 1.35, 1.75}) {
            int bx = (int) Math.floor(pos.x + dirX * dist);
            int bz = (int) Math.floor(pos.z + dirZ * dist);
            if (bx == ctx.playerFeet().x && bz == ctx.playerFeet().z) {
                continue;
            }
            BlockPos footP = new BlockPos(bx, feetY, bz);
            BlockPos headP = new BlockPos(bx, feetY + 1, bz);
            boolean footSolid = !MovementHelper.canWalkThrough(bsi, bx, feetY, bz);
            boolean headSolid = !MovementHelper.canWalkThrough(bsi, bx, feetY + 1, bz);
            boolean footPlaced = footSolid && core.isPlayerPlacedBlock(footP.getX(), footP.getY(), footP.getZ());
            boolean headPlaced = headSolid && core.isPlayerPlacedBlock(headP.getX(), headP.getY(), headP.getZ());
            if (footPlaced || headPlaced) {
                wallBottom = footPlaced ? footP : null;
                wallTop = headPlaced ? headP : null;
                break;
            }
        }
        if (wallBottom == null && wallTop == null) {
            bridgeWallPillarTicks = 0;
            return false;
        }

        boolean twoHigh = wallBottom != null && wallTop != null;
        boolean ceilingClear = MovementHelper.canWalkThrough(bsi, ctx.playerFeet().x, feetY + 2, ctx.playerFeet().z)
                && MovementHelper.canWalkThrough(bsi, ctx.playerFeet().x, feetY + 3, ctx.playerFeet().z);
        boolean narrowBridge = env16 != null && (env16.voidDist()[4] < 2.2 || env16.voidDist()[12] < 2.2);
        BlockPos breakTarget = wallBottom != null ? wallBottom : wallTop;
        BlockState breakState = bsi.get0(breakTarget.getX(), breakTarget.getY(), breakTarget.getZ());
        boolean hasFastTool = hasFastBreakToolFor(ctx.player(), breakState);
        int bridgeBlocks = countHotbarBridgeBlocks(ctx.player());

        BridgeWallCounter counter = chooseBridgeWallCounter(hasFastTool, twoHigh, ceilingClear, bridgeBlocks, narrowBridge);
        if (counter == BridgeWallCounter.NONE) {
            bridgeWallPillarTicks = 0;
            return false;
        }

        if (counter == BridgeWallCounter.STEP_JUMP) {
            bridgeWallPillarTicks = 0;
            WarriorLoadout loadout = scanWarriorLoadout(ctx.player().getInventory());
            selectOptimalMeleeWeapon(target, bodyDist, loadout, env16);
            ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(desiredRot, false, true);
            ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
            baritone.getInputOverrideHandler().clearCombatKeysPreservingHysteresis();
            baritone.getInputOverrideHandler().setDesiredMovementYaw(desiredRot.getYaw());
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
            if (ctx.player().onGround()) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
            return true;
        }

        if (counter == BridgeWallCounter.HIGH_GROUND_PILLAR) {
            if (++bridgeWallPillarTicks > 14) {
                bridgeWallPillarTicks = 0;
                counter = BridgeWallCounter.FAST_TOOL_BOTTOM_BREAK;
            } else if (b.getInventoryBehavior().autoSwapHotbarBridgeBlock(0)) {
                BlockPos belowFeet = ctx.playerFeet().below();
                Vec3 floorTop = new Vec3(belowFeet.getX() + 0.5, belowFeet.getY() + 1.0, belowFeet.getZ() + 0.5);
                Rotation downRot = RotationUtils.calcRotationFromVec3d(eyePos, floorTop, ctx.playerRotations());
                baritone.getLookBehavior().setBridgeAnchorMode(true);
                baritone.getLookBehavior().setUrgentClutchMode(true);
                ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(downRot, true, true);
                ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
                baritone.getInputOverrideHandler().clearCombatKeysPreservingHysteresis();
                if (ctx.player().onGround()) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                } else if (ctx.player().getY() - feetY >= 0.65) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    // Nudge gently forward onto the top of the enemy wall once we clear feetY+1
                    baritone.getInputOverrideHandler().setDesiredMovementYaw(desiredRot.getYaw());
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                }
                return true;
            }
        }

        // FAST_TOOL_BOTTOM_BREAK: swap straight to shears/axe/pickaxe, break both bottom & top of a 2-high wall in ~300ms!
        bridgeWallPillarTicks = 0;
        Vec3 hitCenter = VecUtils.getBlockPosCenter(breakTarget);
        Rotation breakRot = RotationUtils.calcRotationFromVec3d(eyePos, hitCenter, ctx.playerRotations());
        baritone.getLookBehavior().setBridgeAnchorMode(true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(breakRot, true, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
        baritone.getInputOverrideHandler().clearCombatKeysPreservingHysteresis();
        if (eyePos.distanceTo(hitCenter) > 2.45 && env16 != null && env16.voidDist()[0] >= 1.5) {
            baritone.getInputOverrideHandler().setDesiredMovementYaw(desiredRot.getYaw());
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        }
        MovementHelper.switchToBestToolFor(ctx, breakState);
        HitResult over = ctx.objectMouseOver();
        boolean hittingEnemy = over != null && over.getType() == HitResult.Type.ENTITY;
        if (!hittingEnemy && (ctx.isLookingAt(breakTarget) || over instanceof BlockHitResult)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        }
        return true;
    }

    private boolean tryTacticalBridgeBlockTrap(
            Vec3 eyePos,
            LivingEntity target,
            ThreatSnapshot primary,
            Rotation desiredRot,
            double bodyDist,
            RayEnv16 env16
    ) {
        if (ctx.player() == null || ctx.world() == null || !(baritone instanceof Baritone b) || env16 == null) {
            bridgeBlockTrapStepTicks = 0;
            activeBridgeTrapFloor = null;
            return false;
        }
        // Finish an in-progress 1-block trap (max 6 ticks = 300ms so we never stall with blocks out!)
        if (bridgeBlockTrapStepTicks > 0 && activeBridgeTrapFloor != null) {
            bridgeBlockTrapStepTicks--;
            BlockPos placePos = activeBridgeTrapFloor.above();
            if (!ctx.world().getBlockState(placePos).canBeReplaced() || bodyDist < 2.6) {
                bridgeBlockTrapStepTicks = 0;
                activeBridgeTrapFloor = null;
                WarriorLoadout loadout = scanWarriorLoadout(ctx.player().getInventory());
                selectOptimalMeleeWeapon(target, bodyDist, loadout, env16);
                return false;
            }
            if (!b.getInventoryBehavior().autoSwapHotbarBridgeBlock(0)) {
                bridgeBlockTrapStepTicks = 0;
                activeBridgeTrapFloor = null;
                return false;
            }
            Vec3 topFace = new Vec3(activeBridgeTrapFloor.getX() + 0.5, activeBridgeTrapFloor.getY() + 1.0, activeBridgeTrapFloor.getZ() + 0.5);
            Rotation placeRot = RotationUtils.calcRotationFromVec3d(eyePos, topFace, ctx.playerRotations());
            baritone.getLookBehavior().setUrgentClutchMode(true);
            ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(placeRot, true, true);
            ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
            baritone.getInputOverrideHandler().clearCombatKeysPreservingHysteresis();
            // Do not walk face-first into the enemy's sword while placing the trap block!
            HitResult live = ctx.objectMouseOver();
            if (live instanceof BlockHitResult bhr
                    && bhr.getType() == HitResult.Type.BLOCK
                    && bhr.getBlockPos().equals(activeBridgeTrapFloor)
                    && bhr.getDirection() == net.minecraft.core.Direction.UP) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                bridgeBlockTrapStepTicks = 0;
                activeBridgeTrapFloor = null;
                lastBridgeBlockTrapTick = tickCounter;
            }
            return true;
        }

        // Only trigger on a genuine narrow void bridge when an opponent is charging straight at us (3.5..5.2m)
        boolean narrowBridge = env16.voidDist()[4] < 2.1 && env16.voidDist()[12] < 2.1 && env16.voidDist()[0] >= 2.3;
        if (!narrowBridge || !ctx.player().onGround() || tickCounter - lastBridgeBlockTrapTick < 65L) {
            return false;
        }
        if (bodyDist < 3.4 || bodyDist > 5.2 || primary.radialClosingSpeed() < 0.04) {
            return false;
        }
        if (countHotbarBridgeBlocks(ctx.player()) < 6) {
            return false;
        }
        double yawRad = Math.toRadians(desiredRot.getYaw());
        int fx = (int) Math.floor(ctx.player().getX() - Math.sin(yawRad) * 1.9);
        int fy = ctx.playerFeet().y - 1;
        int fz = (int) Math.floor(ctx.player().getZ() + Math.cos(yawRad) * 1.9);
        BlockPos floorPos = new BlockPos(fx, fy, fz);
        BlockPos placePos = floorPos.above();
        if (ctx.world().getBlockState(floorPos).canBeReplaced() || !ctx.world().getBlockState(placePos).canBeReplaced()) {
            return false;
        }
        // Do not place inside our hitbox or target's hitbox
        AABB cellBox = new AABB(placePos);
        if (ctx.player().getBoundingBox().inflate(0.1).intersects(cellBox) || target.getBoundingBox().intersects(cellBox)) {
            return false;
        }
        activeBridgeTrapFloor = floorPos;
        bridgeBlockTrapStepTicks = 5;
        return tryTacticalBridgeBlockTrap(eyePos, target, primary, desiredRot, bodyDist, env16);
    }

    private static boolean hasFastBreakToolFor(net.minecraft.client.player.LocalPlayer player, BlockState state) {
        if (player == null || state == null) {
            return false;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (!st.isEmpty() && st.getDestroySpeed(state) >= 3.5f) {
                return true;
            }
        }
        return false;
    }

    private static int countHotbarBridgeBlocks(net.minecraft.client.player.LocalPlayer player) {
        if (player == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack st = player.getInventory().getItem(i);
            if (!st.isEmpty() && st.getItem() instanceof net.minecraft.world.item.BlockItem) {
                total += st.getCount();
            }
        }
        return total;
    }

    private boolean tryFlankOrStepOverObstacle(
            Vec3 eyePos,
            LivingEntity target,
            Rotation desiredRot,
            double bodyDist,
            RayEnv16 env16
    ) {
        if (ctx.player() == null || ctx.world() == null || env16 == null) {
            return false;
        }
        // Find safest unblocked side/forward kite vector around placed wool without staring down into blocks
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        int feetY = ctx.playerFeet().y;
        Vec3 pos = ctx.player().position();
        int bestRay = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 16; i++) {
            if (env16.voidDist()[i] < 1.8) {
                continue;
            }
            float candYaw = desiredRot.getYaw() + i * 22.5f;
            float diffFromDirect = Math.abs(Rotation.normalizeYaw(candYaw - desiredRot.getYaw()));
            if (diffFromDirect > 95.0f) {
                continue;
            }
            double radYaw = Math.toRadians(candYaw);
            int stepX = (int) Math.floor(pos.x - Math.sin(radYaw) * 1.15);
            int stepZ = (int) Math.floor(pos.z + Math.cos(radYaw) * 1.15);
            boolean canStepJump = MovementHelper.canWalkThrough(bsi, stepX, feetY + 1, stepZ)
                    && MovementHelper.canWalkThrough(bsi, stepX, feetY + 2, stepZ);
            if (!env16.safe()[i] && (!canStepJump || diffFromDirect > 48.0f)) {
                continue;
            }
            double sc = env16.wallDist()[i] - diffFromDirect * 0.035;
            if (canStepJump) {
                sc += 1.1;
            }
            if (env16.voidDist()[i] >= 2.2) {
                sc += 0.5;
            }
            if (sc > bestScore) {
                bestScore = sc;
                bestRay = i;
            }
        }
        if (bestRay < 0) {
            return false;
        }
        float flankYaw = desiredRot.getYaw() + bestRay * 22.5f;
        double flankRad = Math.toRadians(flankYaw);
        int jumpX = (int) Math.floor(pos.x - Math.sin(flankRad) * 1.15);
        int jumpZ = (int) Math.floor(pos.z + Math.cos(flankRad) * 1.15);
        boolean jumpClear = MovementHelper.canWalkThrough(bsi, jumpX, feetY + 1, jumpZ)
                && MovementHelper.canWalkThrough(bsi, jumpX, feetY + 2, jumpZ);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(desiredRot, false, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
        baritone.getInputOverrideHandler().clearCombatKeysPreservingHysteresis();
        prevKiteYaw = flankYaw;
        baritone.getInputOverrideHandler().setDesiredMovementYaw(flankYaw);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
        if (ctx.player().onGround()
                && (ctx.player().horizontalCollision || env16.wallDist()[0] < 1.6)
                && jumpClear
                && hasCombatJumpCeilingClearance(flankYaw)
                && env16.voidDist()[bestRay] >= 2.2) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        }
        return true;
    }

    private boolean tryDirectBarrierBreach(
            Vec3 eyePos,
            LivingEntity target,
            float yawToTarget,
            boolean requireNoLos,
            double bodyDist
    ) {
        if (!Baritone.settings().allowBreak.value) {
            return false;
        }
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        int feetY = ctx.playerFeet().y;
        double playerX = ctx.player().position().x;
        double playerZ = ctx.player().position().z;
        double targetHorizDist = Math.hypot(target.getX() - playerX, target.getZ() - playerZ);
        AABB targetBox = target.getBoundingBox();
        double tgtCenterX = targetBox.getCenter().x;
        double tgtCenterZ = targetBox.getCenter().z;

        double[][] candidates = requireNoLos
                ? new double[][]{
                {0.0, 0.50, 0.0},
                {-0.85, 0.30, 0.0},
                {0.0, 0.50, 18.0},
                {0.0, 0.50, -18.0},
                {-0.70, 0.30, 18.0},
                {-0.70, 0.30, -18.0},
                {0.0, 0.50, 34.0},
                {0.0, 0.50, -34.0}
        }
                : new double[][]{
                {-0.88, 0.28, 0.0},
                {-0.88, 0.28, 18.0},
                {-0.88, 0.28, -18.0},
                {0.0, 0.26, 0.0}
        };

        BlockPos bestPos = null;
        BlockState bestState = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (double[] spec : candidates) {
            double startYOff = spec[0];
            double tgtYFrac = spec[1];
            double yawOff = spec[2];

            Vec3 rayStart = eyePos.add(0.0, startYOff, 0.0);
            double aimY = targetBox.minY + (targetBox.maxY - targetBox.minY) * tgtYFrac;
            Vec3 baseAim = new Vec3(tgtCenterX, aimY, tgtCenterZ);
            Vec3 delta = baseAim.subtract(rayStart);
            if (yawOff != 0.0) {
                double rad = Math.toRadians(yawOff);
                double cos = Math.cos(rad);
                double sin = Math.sin(rad);
                double rx = delta.x * cos - delta.z * sin;
                double rz = delta.x * sin + delta.z * cos;
                double hLen = Math.hypot(rx, rz);
                double scale = hLen > 1e-4 ? Math.min(4.25, Math.max(2.2, bodyDist)) / hLen : 1.0;
                delta = new Vec3(rx * scale, delta.y, rz * scale);
            }
            Vec3 rayEnd = rayStart.add(delta);
            BlockHitResult hit = ctx.world().clip(new ClipContext(rayStart, rayEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player()));
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
                continue;
            }
            BlockPos hitPos = hit.getBlockPos();
            if (hitPos.getY() < feetY || hitPos.getY() > feetY + 2) {
                continue;
            }
            Vec3 hitCenter = VecUtils.getBlockPosCenter(hitPos);
            double dist = eyePos.distanceTo(hitCenter);
            if (dist > (requireNoLos ? 4.25 : 3.85)) {
                continue;
            }
            if (!requireNoLos) {
                double hitHoriz = Math.hypot(hitCenter.x - playerX, hitCenter.z - playerZ);
                if (hitHoriz >= targetHorizDist - 0.10) {
                    continue;
                }
            }
            BlockState state = bsi.get0(hitPos.getX(), hitPos.getY(), hitPos.getZ());
            if (Baritone.settings().bedwarsMode.value
                    && baritone instanceof Baritone b
                    && b.getBedwarsArenaMemory() != null
                    && b.getBedwarsArenaMemory().getActiveCore() != null
                    && !b.getBedwarsArenaMemory().getActiveCore().isPlayerPlacedBlock(hitPos.getX(), hitPos.getY(), hitPos.getZ())) {
                // Universal BedWars invariant: NEVER try to mine unbreakable map terrain (e.g. map glass walls)
                continue;
            }
            float speed = state.getDestroySpeed(ctx.world(), hitPos);
            if (speed < 0.0f || speed > 8.0f
                    || Baritone.settings().blocksToDisallowBreaking.value.contains(state.getBlock())
                    || MovementHelper.avoidBreaking(bsi, hitPos.getX(), hitPos.getY(), hitPos.getZ(), state)
                    || (Baritone.settings().humanMode.value && MovementHelper.isProtectedStructureBlock(state.getBlock()))) {
                continue;
            }
            double score = dist + speed * 0.40 + (yawOff == 0.0 ? 0.0 : 0.35);
            if (score < bestScore) {
                bestScore = score;
                bestPos = hitPos;
                bestState = state;
            }
        }

        if (bestPos == null || bestState == null) {
            return false;
        }
        Vec3 hitCenter = VecUtils.getBlockPosCenter(bestPos);
        double dist = eyePos.distanceTo(hitCenter);
        Rotation breakRot = RotationUtils.calcRotationFromVec3d(eyePos, hitCenter, ctx.playerRotations());
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(breakRot, false, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setDesiredMovementYaw(yawToTarget);
        if (dist > 2.35) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        }
        if (baritone.getInventoryBehavior().isSlotSwitchSafe()) {
            MovementHelper.switchToBestToolFor(ctx, bestState);
        }
        InputOverrideHandler io = baritone.getInputOverrideHandler();
        HitResult over = ctx.objectMouseOver();
        boolean entityInWay = Baritone.settings().humanMode.value && over != null && over.getType() == HitResult.Type.ENTITY;
        if (!entityInWay && io.canSwitchFromAttackToBreak() && baritone.getInventoryBehavior().isHotbarReadyForAction()) {
            io.setInputForceState(Input.CLICK_LEFT, true);
        }
        return true;
    }

    private boolean updateMultiThreatSpacing(
            LivingEntity target,
            List<ThreatSnapshot> threats,
            int primaryIdx,
            float desiredYaw,
            double boxDist,
            CombatRuleset ruleset,
            float cooldown,
            RayEnv16 env
    ) {
        if (strafeFlipCooldown > 0) {
            strafeFlipCooldown--;
        }
        boolean opponentAimingClose = target != null && isOpponentAimingAtUs(target, ctx.player(), 10.0f);
        if (--strafeSwitchTicks <= 0 || (opponentAimingClose && strafeFlipCooldown <= 0 && boxDist <= 3.15)) {
            strafeDir = -strafeDir;
            strafeFlipCooldown = 12;
            // Smooth, wide human strafe arcs (10-20t) with zero 5 Hz L/R jitter
            strafeSwitchTicks = 10 + (int) (rand.nextDouble() * 11.0);
        }

        ThreatSnapshot primary = threats.get(primaryIdx);
        Vec3 pos = ctx.player().position();
        boolean openFooting = env.safe()[0] && env.safe()[4] && env.safe()[8] && env.safe()[12];
        boolean safeBackward = env.safe()[8]
                && env.wallDist()[8] >= 1.95
                && env.voidDist()[8] >= (Baritone.settings().bedwarsMode.value ? 4.2 : 2.35)
                && !hasThreatInCone(threats, primaryIdx, pos.x, pos.z, desiredYaw + 180.0f, 3.4);

        // Edge-safe + 2D void-gradient strafe guard: keep solid ground behind our own back
        // while circling to angle knockback hits directly toward the enemy's nearest void cliff!
        int preferredStrafe = CombatPhysicsModel.computeVoidPressureStrafeSign(env.voidDist(), env.safe(), strafeDir);
        boolean bothSidesSafe = env.safe()[4] && env.voidDist()[4] >= 2.50 && env.safe()[12] && env.voidDist()[12] >= 2.50;
        if (bothSidesSafe && Baritone.settings().bedwarsMode.value && target != null) {
            BedwarsMapCore core = baritone.getBedwarsArenaMemory().getActiveCore();
            if (core != null) {
                double yawRad = Math.toRadians(desiredYaw);
                double rightX = -Math.cos(yawRad);
                double rightZ = -Math.sin(yawRad);
                double[] ourSafeGrad = new double[2];
                double[] enemySafeGrad = new double[2];
                core.computeVoidSafeDirection(Mth.floor(pos.x), Mth.floor(pos.z), ourSafeGrad);
                core.computeVoidSafeDirection(target.getBlockX(), target.getBlockZ(), enemySafeGrad);
                // Positive dot -> right (+1) moves toward solid interior / lines up knockback toward cliff behind enemy
                double ownSafety = ourSafeGrad[0] * rightX + ourSafeGrad[1] * rightZ;
                double knockAngle = enemySafeGrad[0] * rightX + enemySafeGrad[1] * rightZ;
                double combined = ownSafety * 1.10 + knockAngle * 0.95;
                if (combined > 0.30) {
                    preferredStrafe = 1;
                } else if (combined < -0.30) {
                    preferredStrafe = -1;
                }
            }
        }
        if (preferredStrafe != 0) {
            strafeDir = preferredStrafe;
        }

        if (primary.creeperSwelling() && boxDist < 4.5) {
            float kiteYaw = chooseContextKiteYaw16(threats, primaryIdx, pos.x, pos.z, desiredYaw, prevKiteYaw, true, true, preferredStrafe, env);
            if (!Float.isNaN(kiteYaw)) {
                prevKiteYaw = kiteYaw;
                baritone.getInputOverrideHandler().setDesiredMovementYaw(kiteYaw);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            }
            return false;
        }

        // HurtTime & distance-gated W-Tap / S-Tap spacing ONLY on fresh hit (hurtTime >= 9)
        boolean voidKnockback = brainCombatMode == CombatMode.VOID_KNOCKBACK;
        boolean freshHit = primary.hurtTime() >= 9
                && (primary.id() != lastSpacedTargetId || lastSpacedTargetHurtTime < 9);
        if (freshHit) {
            SpacingAction resetAction = chooseHurtTimeSpacingReset(
                    primary.hurtTime(),
                    boxDist,
                    ruleset == CombatRuleset.CLASSIC_1_8,
                    voidKnockback
            );
            if (resetAction == SpacingAction.S_TAP && safeBackward) {
                sTapTicks = Math.max(sTapTicks, boxDist < 2.10 ? 3 : 2);
                recordBlackboxPvpEvent("STAP", "dist=" + String.format("%.2f", boxDist) + ",ticks=" + sTapTicks);
            } else if (resetAction == SpacingAction.W_TAP) {
                wTapTicks = Math.max(wTapTicks, 2);
                recordBlackboxPvpEvent("WTAP", "dist=" + String.format("%.2f", boxDist) + ",ticks=2");
            }
        }
        lastSpacedTargetId = primary.id();
        lastSpacedTargetHurtTime = primary.hurtTime();

        // Legit JumpReset on fresh knockback (player.hurtTime >= 9) when grounded and void-safe behind us
        int pHurt = ctx.player().hurtTime;
        boolean didJumpReset = false;
        if (pHurt >= 9 && lastJumpResetPlayerHurtTime < 9) {
            double backVoidDist = env.voidDist()[8];
            boolean allowEdgeJump = !Baritone.settings().bedwarsMode.value;
            if (shouldTriggerJumpReset(pHurt, ctx.player().onGround(), boxDist > 1.65, ctx.player().isBlocking(), backVoidDist, allowEdgeJump)
                    && hasCombatJumpCeilingClearance(desiredYaw)
                    && rand.nextDouble() < 0.85) {
                sTapTicks = 0;
                wTapTicks = 0;
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                didJumpReset = true;
                recordBlackboxPvpEvent("JUMPRESET", "hurtTime=" + pHurt + ",backVoid=" + String.format("%.2f", backVoidDist));
            }
        }
        lastJumpResetPlayerHurtTime = pHurt;

        // Keep lateral strafing active during W-tap / S-tap instead of freezing movement!
        if (!didJumpReset && sTapTicks > 0) {
            sTapTicks--;
            baritone.getInputOverrideHandler().setDesiredMovementYaw(null);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            if (safeBackward) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            }
            if (preferredStrafe > 0 && env.safe()[4] && env.voidDist()[4] >= 2.2) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, true);
            } else if (preferredStrafe < 0 && env.safe()[12] && env.voidDist()[12] >= 2.2) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, true);
            }
            return openFooting;
        }
        if (!didJumpReset && wTapTicks > 0) {
            wTapTicks--;
            baritone.getInputOverrideHandler().setDesiredMovementYaw(null);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            if (preferredStrafe > 0 && env.safe()[4] && env.voidDist()[4] >= 2.2) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, true);
            } else if (preferredStrafe < 0 && env.safe()[12] && env.voidDist()[12] >= 2.2) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, true);
            }
            return openFooting;
        }

        boolean primaryRanged = primary.ranged();
        boolean urgentSecondary = hasUrgentSecondaryCharger(threats, primaryIdx, 3.6, 8.0);
        boolean defensiveKite = brainCombatMode == CombatMode.DEFENSIVE_KITE;
        boolean comboLockHolding = ruleset == CombatRuleset.CLASSIC_1_8
                && !primaryRanged
                && !defensiveKite
                && (primary.hurtTime() > 0 || (target != null && !target.onGround()));
        boolean wantAttackSpacing = !primaryRanged && (
                defensiveKite
                        || comboLockHolding
                        || (ruleset == CombatRuleset.MODERN_COOLDOWN && cooldown < 0.85f && boxDist < 2.95)
                        || (threats.size() >= 2 && urgentSecondary && boxDist < 2.65 && cooldown < 0.85f)
        );
        boolean retreating = !primaryRanged
                && (((urgentSecondary && boxDist < 2.35) || boxDist < 2.28)
                    && ruleset == CombatRuleset.MODERN_COOLDOWN
                    && cooldown < 0.85f
                || (defensiveKite && ruleset == CombatRuleset.MODERN_COOLDOWN && boxDist < 2.75 && cooldown < 0.90f));
        if (voidKnockback && primary.hurtTime() >= 9 && boxDist < 3.10 && !defensiveKite) {
            wTapTicks = Math.max(wTapTicks, 2);
        }

        float kiteYaw = chooseContextKiteYaw16(
                threats,
                primaryIdx,
                pos.x,
                pos.z,
                desiredYaw,
                prevKiteYaw,
                wantAttackSpacing,
                retreating,
                preferredStrafe,
                env
        );
        if (!Float.isNaN(kiteYaw)) {
            prevKiteYaw = kiteYaw;
            baritone.getInputOverrideHandler().setDesiredMovementYaw(kiteYaw);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);

            float moveRelYaw = Math.abs(Rotation.normalizeYaw(kiteYaw - desiredYaw));
            boolean closingForward = moveRelYaw <= 50.0f && boxDist > (primaryRanged ? 2.15 : 2.25);
            boolean critDescending = ruleset == CombatRuleset.MODERN_COOLDOWN && !ctx.player().onGround();
            boolean allowSprint = closingForward && !retreating && !critDescending
                    && (ruleset == CombatRuleset.CLASSIC_1_8 || (!wantAttackSpacing && boxDist > 2.68) || (primaryRanged && !urgentSecondary && cooldown >= 0.86f));
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, allowSprint);
        }
        return openFooting;
    }

    private void stepClassic18Attack(
            LivingEntity target,
            Vec3 eyePos,
            double boxDist,
            boolean crosshairAligned,
            HumanProfile profile,
            WarriorLoadout loadout
    ) {
        if (blockHitTicks > 0) {
            blockHitTicks--;
            ItemStack mainHand = ctx.player().getMainHandItem();
            if (canBlockHitWithMainHand(mainHand)) {
                // Dedicated 1-tick block-hit: hold RMB on a non-attack tick so LMB and RMB never fire together.
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                clickAccumulator = Math.min(0.75, clickAccumulator);
                return;
            }
        }
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, false);
        if (boxDist > 3.35) {
            clickAccumulator = Math.min(clickAccumulator, 0.35);
            hitSelectWaitTicks = 0;
            hitSelectLogEmitted = false;
            return;
        }
        // HitSelect gating on neutral ground exchange (active in 2.65..2.98m; tight 2-tick cap for first-hit combo advantage)
        boolean shieldBreakReady = target.isBlocking() && loadout.axeSlot() >= 0;
        boolean hitSelectReady = shieldBreakReady || boxDist <= 2.65 || shouldHitSelectEngage(
                ctx.player().hurtTime,
                target.hurtTime,
                target.onGround(),
                boxDist,
                hitSelectWaitTicks,
                2
        );
        if (!hitSelectReady) {
            hitSelectWaitTicks++;
            if (!hitSelectLogEmitted) {
                hitSelectLogEmitted = true;
                recordBlackboxPvpEvent("HITSELECT_HOLD", "dist=" + String.format("%.2f", boxDist));
            }
            return;
        }
        hitSelectWaitTicks = 0;

        // Competitive human 1.8 BedWars PvP cadence (8.5-10.0 CPS legit log-normal spacing, zero double-throttle)
        boolean strictLegitSpacing = Baritone.settings().humanMode.value
                || Baritone.settings().bedwarsMode.value
                || baritone.getBedwarsProcess().isActive();
        double rawCps = profile.cpsMean() >= 8.0 ? profile.cpsMean() : 9.8;
        double baseCps = strictLegitSpacing
                ? Math.max(8.5, Math.min(10.0, rawCps))
                : Math.max(9.5, Math.min(12.0, profile.cpsMean() > 9.0 ? profile.cpsMean() : 11.5));

        InputOverrideHandler io = baritone.getInputOverrideHandler();
        if (io.getBlockBreakHelper().isHitting() || !io.canSwitchFromBreakToAttack()) {
            io.getBlockBreakHelper().stopBreakingBlock();
            io.setInputForceState(Input.CLICK_LEFT, false);
            return;
        }
        Minecraft mc = ctx.minecraft();
        boolean blockedByBlock = mc != null && mc.hitResult instanceof BlockHitResult;
        if (!canDispatchLegitAttack(mc != null ? mc.hitResult : null, target, targetFilter, boxDist, ATTACK_REACH, crosshairAligned, blockedByBlock)
                || !isClassicSpacingAllowed(tickCounter, lastClassicAttackTick, prevClassicAttackDt, strictLegitSpacing)
                || tickCounter < nextClassicAttackAllowedTick) {
            return;
        }
        prevClassicAttackDt = tickCounter - lastClassicAttackTick;
        lastClassicAttackTick = tickCounter;
        nextClassicAttackAllowedTick = tickCounter + CombatPhysicsModel.nextHumanAttackDelayTicks(
                baseCps, strictLegitSpacing, rand.nextDouble(), rand.nextDouble()
        );

        dispatchLegitAttackClick(target, CombatRuleset.CLASSIC_1_8, boxDist, eyePos);
        ItemStack mainHand = ctx.player().getMainHandItem();
        if (canBlockHitWithMainHand(mainHand) && boxDist <= 2.95) {
            blockHitTicks = 1;
        }
    }

    /**
     * Returns true only when the held item actually supports blocking on this 1.21+ client;
     * pressing right-click with a plain sword sends illegal {@code INTERACT_AT} packets on enemies.
     */
    public static boolean canBlockHitWithMainHand(ItemStack mainHand) {
        return mainHand != null && !mainHand.isEmpty() && mainHand.get(DataComponents.BLOCKS_ATTACKS) != null;
    }

    private void stepModernAttack(
            LivingEntity target,
            Vec3 eyePos,
            double boxDist,
            boolean crosshairAligned,
            boolean allowJumpCrit,
            boolean swarmed,
            boolean shieldBreakActive,
            WarriorLoadout loadout
    ) {
        if (boxDist > ATTACK_REACH + 0.25) {
            critHoldTicks = 0;
            return;
        }
        if (shieldBreakActive && shieldAxeDelayTicks > 0) {
            // Mandatory 2-tick human swap settle delay after selecting Axe before disabling shield
            return;
        }
        float cooldown = ctx.player().getAttackStrengthScale(0.5f);
        boolean axeBreakReady = shieldBreakActive && loadout.axeSlot() >= 0 && cooldown >= 0.65f;
        float requiredCooldown = axeBreakReady ? 0.65f : (swarmed ? 0.94f : 0.92f);

        // Initiate jump crit cleanly right after stagger/knockback via genuine sprint/forward release
        if (shouldJumpCritModern(allowJumpCrit, ctx.player().onGround(), cooldown, boxDist, target.hurtTime, target.isBlocking())
                && hasCombatJumpCeilingClearance(ctx.player().getYRot())) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            if (ctx.player().isSprinting()) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            }
        }

        // Hold up to 4 ticks mid-air for downward fall (vel.y < -0.04) & release sprint inputs for a true 1.5x crit
        boolean airborne = !ctx.player().onGround();
        if (airborne && cooldown >= requiredCooldown && !axeBreakReady) {
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            if (ctx.player().isSprinting()) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            }
            if (ctx.player().getDeltaMovement().y >= -0.04 && boxDist <= 2.85 && critHoldTicks < 4) {
                critHoldTicks++;
                return;
            }
        }
        critHoldTicks = 0;

        Minecraft mc = ctx.minecraft();
        boolean blockedByBlock = mc != null && mc.hitResult instanceof BlockHitResult;
        boolean raycastVerified = canDispatchLegitAttack(
                mc != null ? mc.hitResult : null,
                target,
                targetFilter,
                boxDist,
                ATTACK_REACH,
                crosshairAligned,
                blockedByBlock
        );

        if (cooldown >= requiredCooldown && raycastVerified) {
            InputOverrideHandler io = baritone.getInputOverrideHandler();
            if (io.getBlockBreakHelper().isHitting() || !io.canSwitchFromBreakToAttack()) {
                io.getBlockBreakHelper().stopBreakingBlock();
                io.setInputForceState(Input.CLICK_LEFT, false);
                return;
            }
            boolean readyAfterJitter = true;
            if (!airborne && !axeBreakReady) {
                if (modernCooldownWaitJitter == 0 && rand.nextDouble() < 0.12) {
                    modernCooldownWaitJitter = 1;
                    readyAfterJitter = false;
                } else if (modernCooldownWaitJitter > 0) {
                    modernCooldownWaitJitter--;
                    readyAfterJitter = (modernCooldownWaitJitter == 0);
                }
            }
            if (readyAfterJitter) {
                dispatchLegitAttackClick(target, CombatRuleset.MODERN_COOLDOWN, boxDist, eyePos);
                if (axeBreakReady && tickCounter - lastShieldBreakTick >= 4L) {
                    lastShieldBreakTick = tickCounter;
                    shieldSwapBackDelayTicks = 3;
                    recordBlackboxPvpEvent("SHIELD_BREAK", "dist=" + String.format("%.2f", boxDist));
                }
            }
        }
    }

    /**
     * Warrior gear helpers (1.8 blockhit / hit-select, Modern Axe-vs-Shield, Shield defense, Mace, Bow, Rod,
     * BedWars KB Stick, Fireballs, Ender Pearls, and Snowballs/Eggs/Silverfish).
     */
    public record WarriorLoadout(
            int swordSlot,
            int axeSlot,
            int maceSlot,
            int bowSlot,
            int rodSlot,
            int shieldSlot,
            boolean offhandShield,
            boolean hasArrows,
            int kbStickSlot,
            int fireballSlot,
            int pearlSlot,
            int throwableSlot
    ) {
        public boolean hasShield() {
            return offhandShield || shieldSlot >= 0;
        }
    }

    public static boolean shouldHitSelect18(int targetHurtTime, double boxDist, boolean shieldBreakReady) {
        if (shieldBreakReady) {
            return true;
        }
        if (boxDist < 2.15) {
            return targetHurtTime <= 4;
        }
        return targetHurtTime <= 2;
    }

    /**
     * HitSelect engage gate on close approach: briefly pauses opening click at 2.65..3.35m until
     * player takes KB, opponent becomes airborne, opponent already has hurtTime, or wait cap expires.
     */
    public static boolean shouldHitSelectEngage(
            int playerHurtTime,
            int targetHurtTime,
            boolean targetOnGround,
            double boxDist,
            int waitTicks,
            int maxWaitTicks
    ) {
        if (playerHurtTime > 0 || !targetOnGround || targetHurtTime > 0 || boxDist <= 2.65) {
            return true;
        }
        return waitTicks >= Math.max(0, maxWaitTicks);
    }

    /**
     * HurtTime and distance-gated W-Tap / S-Tap spacing reset triggered strictly on fresh hits
     * ({@code targetHurtTime >= 9}), holding full W+Sprint beyond 2.85m.
     */
    public static SpacingAction chooseHurtTimeSpacingReset(
            int targetHurtTime,
            double boxDist,
            boolean isClassic18,
            boolean isVoidKnockback
    ) {
        if (targetHurtTime < 9) {
            return SpacingAction.NONE;
        }
        if (boxDist < 2.35) {
            return SpacingAction.S_TAP;
        }
        if (boxDist <= 2.85 || (isVoidKnockback && boxDist <= 3.10) || (isClassic18 && boxDist <= 3.35)) {
            return SpacingAction.W_TAP;
        }
        return SpacingAction.NONE;
    }

    /**
     * Returns whether legit vanilla JumpReset should pulse JUMP on fresh incoming knockback.
     */
    public static boolean shouldTriggerJumpReset(
            int playerHurtTime,
            boolean onGround,
            boolean movingForward,
            boolean blocking,
            double backVoidDist,
            boolean allowEdgeJump
    ) {
        if (playerHurtTime < 9 || !onGround || !movingForward || blocking) {
            return false;
        }
        return allowEdgeJump || backVoidDist >= 3.0;
    }

    /**
     * Returns whether a blocking defender's horizontal facing opposes the attacker-to-defender
     * vector (shield actively blocking incoming front attacks).
     */
    public static boolean isShieldBlockingUs(Vec3 attackerPos, Vec3 defenderPos, Vec3 defenderLook) {
        if (attackerPos == null || defenderPos == null || defenderLook == null) {
            return false;
        }
        double dx = defenderPos.x - attackerPos.x;
        double dz = defenderPos.z - attackerPos.z;
        double dLen = Math.hypot(dx, dz);
        double lx = defenderLook.x;
        double lz = defenderLook.z;
        double lLen = Math.hypot(lx, lz);
        if (dLen < 1e-5 || lLen < 1e-5) {
            return false;
        }
        double dot = (dx / dLen) * (lx / lLen) + (dz / dLen) * (lz / lLen);
        return dot < 0.0;
    }

    /**
     * Eye-level clamped reach-optimal aim point with subtle velocity lead clamped strictly inside
     * the live target AABB so vanilla {@code mc.pick(1.0F)} raycasts always land on-hitbox.
     */
    public static Vec3 computeReachOptimalAimPoint(
            Vec3 eyePos,
            AABB targetBox,
            Vec3 enemyVel,
            double leadTicks,
            double shrinkRatio
    ) {
        double lx = Math.max(-0.18, Math.min(0.18, enemyVel.x * leadTicks));
        double ly = Math.max(-0.12, Math.min(0.12, enemyVel.y * leadTicks));
        double lz = Math.max(-0.18, Math.min(0.18, enemyVel.z * leadTicks));
        AABB box = targetBox.move(lx, ly, lz);

        double dx = (box.maxX - box.minX) * 0.5 * Math.min(0.45, Math.max(0.0, shrinkRatio));
        double dz = (box.maxZ - box.minZ) * 0.5 * Math.min(0.45, Math.max(0.0, shrinkRatio));
        double minX = box.minX + dx;
        double maxX = box.maxX - dx;
        double minZ = box.minZ + dz;
        double maxZ = box.maxZ - dz;

        double minY = box.minY + 0.15;
        double maxY = Math.max(minY, box.maxY - 0.15);

        double ax = Math.max(minX, Math.min(maxX, eyePos.x));
        double ay = Math.max(minY, Math.min(maxY, eyePos.y));
        double az = Math.max(minZ, Math.min(maxZ, eyePos.z));

        // Clamp final aim strictly inside the live un-shifted hitbox (small 0.06 edge margin) so crosshair never misses!
        double liveMarginXZ = Math.min(0.08, (targetBox.maxX - targetBox.minX) * 0.15);
        double liveMinY = targetBox.minY + 0.12;
        double liveMaxY = Math.max(liveMinY, targetBox.maxY - 0.12);
        ax = Math.max(targetBox.minX + liveMarginXZ, Math.min(targetBox.maxX - liveMarginXZ, ax));
        ay = Math.max(liveMinY, Math.min(liveMaxY, ay));
        az = Math.max(targetBox.minZ + liveMarginXZ, Math.min(targetBox.maxZ - liveMarginXZ, az));
        return new Vec3(ax, ay, az);
    }

    private Vec3 sampleTrueEntityVelocity(LivingEntity target) {
        if (target == null) {
            return Vec3.ZERO;
        }
        Vec3 cur = target.position();
        Vec3 prev = prevEntityPositions.put(target.getId(), cur);
        if (prev == null) {
            return Vec3.ZERO;
        }
        Vec3 delta = cur.subtract(prev);
        if (delta.lengthSqr() > 2.25) {
            return Vec3.ZERO;
        }
        return delta;
    }

    private static boolean isOpponentAimingAtUs(LivingEntity target, Entity player, double maxYawErrDeg) {
        if (target == null || player == null) {
            return false;
        }
        double dx = player.getX() - target.getX();
        double dz = player.getZ() - target.getZ();
        if (Math.hypot(dx, dz) < 1e-4) {
            return false;
        }
        float yawToUs = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return Math.abs(Rotation.normalizeYaw(target.getYRot() - yawToUs)) <= maxYawErrDeg;
    }

    public static boolean shouldUseShieldDefense(
            boolean modernMode,
            boolean hasShield,
            float cooldown,
            double boxDist,
            boolean targetBlocking,
            boolean primaryRanged,
            boolean urgentSecondary,
            boolean creeperSwelling
    ) {
        if (!modernMode || !hasShield || targetBlocking) {
            return false;
        }
        if (creeperSwelling && boxDist < 4.5) {
            return true;
        }
        if (cooldown >= 0.85f && boxDist <= 3.05) {
            return false;
        }
        return primaryRanged || (cooldown < 0.82f && (boxDist < 2.95 || urgentSecondary));
    }

    public static boolean shouldJumpCritModern(
            boolean allowJumpCrit,
            boolean onGround,
            float cooldown,
            double boxDist,
            int targetHurtTime,
            boolean targetBlocking
    ) {
        return allowJumpCrit
                && onGround
                && !targetBlocking
                && cooldown >= 0.84f
                && boxDist >= 2.20
                && boxDist <= 2.95
                && (targetHurtTime >= 4 || cooldown >= 0.98f);
    }

    public static float computeBowPitchDegrees(double horizDist, double dy, double velocityBlocksPerTick) {
        double v = Math.max(1.2, velocityBlocksPerTick);
        double t = horizDist / v;
        double dropCompensatedDy = dy + 0.5 * 0.05 * t * t;
        return Rotation.clampPitch((float) -Math.toDegrees(Math.atan2(dropCompensatedDy, Math.max(0.25, horizDist))));
    }

    private WarriorLoadout scanWarriorLoadout(Inventory inv) {
        int swordSlot = -1;
        double bestSwordScore = -1.0;
        int axeSlot = -1;
        double bestAxeScore = -1.0;
        int maceSlot = -1;
        int bowSlot = -1;
        int rodSlot = -1;
        int shieldSlot = -1;
        int kbStickSlot = -1;
        int bestKbLevel = 0;
        int fireballSlot = -1;
        int pearlSlot = -1;
        int throwableSlot = -1;
        boolean hasArrows = false;

        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getNonEquipmentItems().get(i);
            if (st.isEmpty()) {
                continue;
            }
            if (st.is(Items.MACE)) {
                maceSlot = i;
            } else if (st.is(Items.BOW)) {
                bowSlot = i;
            } else if (st.is(Items.FISHING_ROD)) {
                rodSlot = i;
            } else if (st.is(Items.SHIELD) || st.getItem().components().has(DataComponents.BLOCKS_ATTACKS)) {
                shieldSlot = i;
            } else if (st.is(Items.FIRE_CHARGE) && fireballSlot < 0) {
                fireballSlot = i;
            } else if (st.is(Items.ENDER_PEARL) && pearlSlot < 0) {
                pearlSlot = i;
            } else if ((st.is(Items.SNOWBALL) || st.is(Items.EGG)) && throwableSlot < 0) {
                throwableSlot = i;
            }
            int kbLevel = getKnockbackLevel(st);
            if (kbLevel > bestKbLevel && !isSwordItem(st)) {
                bestKbLevel = kbLevel;
                kbStickSlot = i;
            }
            Weapon weapon = st.getItem().components().get(DataComponents.WEAPON);
            ItemAttributeModifiers mods = st.getItem().components().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
            double dmg = mods.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
            if (weapon != null && weapon.disableBlockingForSeconds() > 0.0f) {
                if (dmg > bestAxeScore) {
                    bestAxeScore = dmg;
                    axeSlot = i;
                }
            } else if (isSwordItem(st) && dmg > bestSwordScore) {
                bestSwordScore = dmg;
                swordSlot = i;
            }
        }
        for (int i = 0; i < inv.getNonEquipmentItems().size(); i++) {
            ItemStack st = inv.getNonEquipmentItems().get(i);
            if (st.is(Items.ARROW) || st.is(Items.SPECTRAL_ARROW) || st.is(Items.TIPPED_ARROW)) {
                hasArrows = true;
                break;
            }
        }
        boolean offhandShield = ctx.player().getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD)
                || ctx.player().getItemBySlot(EquipmentSlot.OFFHAND).getItem().components().has(DataComponents.BLOCKS_ATTACKS);
        return new WarriorLoadout(
                swordSlot, axeSlot, maceSlot, bowSlot, rodSlot, shieldSlot, offhandShield, hasArrows,
                kbStickSlot, fireballSlot, pearlSlot, throwableSlot
        );
    }

    private static int getKnockbackLevel(ItemStack st) {
        if (st == null || st.isEmpty()) {
            return 0;
        }
        ItemEnchantments enchs = st.getEnchantments();
        for (Holder<Enchantment> ench : enchs.keySet()) {
            if (ench.is(Enchantments.KNOCKBACK)) {
                return enchs.getLevel(ench);
            }
        }
        return 0;
    }

    private static boolean isSwordItem(ItemStack st) {
        if (st.isEmpty()) {
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        return path.endsWith("_sword");
    }

    private void selectOptimalMeleeWeapon(LivingEntity target, double boxDist, WarriorLoadout loadout, RayEnv16 env16) {
        if (!baritone.getInventoryBehavior().isSlotSwitchSafe()) {
            return;
        }
        boolean shieldFacingUs = target.isBlocking() && isShieldBlockingUs(
                ctx.player().position(),
                target.position(),
                target.getViewVector(1.0F)
        );
        if (shieldFacingUs && loadout.axeSlot() >= 0) {
            baritone.getInventoryBehavior().safeSelectHotbarSlot(loadout.axeSlot());
            return;
        }
        if (loadout.maceSlot() >= 0 && !ctx.player().onGround() && ctx.player().fallDistance >= 1.75f && boxDist <= 3.15) {
            baritone.getInventoryBehavior().safeSelectHotbarSlot(loadout.maceSlot());
            return;
        }
        if (loadout.kbStickSlot() >= 0 && Baritone.settings().bedwarsMode.value) {
            boolean narrowBridge = env16 != null && (env16.voidDist()[4] < 2.2 || env16.voidDist()[12] < 2.2);
            double enemyVoidExp = 0.0;
            BedwarsMapCore core = baritone.getBedwarsArenaMemory() != null ? baritone.getBedwarsArenaMemory().getActiveCore() : null;
            if (core != null) {
                enemyVoidExp = core.sampleVoidExposure(target.getBlockX(), target.getBlockZ());
            }
            if (CombatPhysicsModel.shouldUseKnockbackStick(true, narrowBridge, enemyVoidExp, target.getHealth())) {
                baritone.getInventoryBehavior().safeSelectHotbarSlot(loadout.kbStickSlot());
                return;
            }
        }
        if (loadout.swordSlot() >= 0) {
            baritone.getInventoryBehavior().safeSelectHotbarSlot(loadout.swordSlot());
        } else if (loadout.axeSlot() >= 0) {
            baritone.getInventoryBehavior().safeSelectHotbarSlot(loadout.axeSlot());
        }
    }

    private boolean tryCombatBridgeFireball(LivingEntity target, Vec3 eyePos, Vec3 enemyVel, double bodyDist) {
        if (bodyDist < 6.0 || bodyDist > 18.0 || tickCounter - lastCombatFireballTick < 40L || ctx.player() == null) {
            return false;
        }
        WarriorLoadout loadout = scanWarriorLoadout(ctx.player().getInventory());
        if (loadout.fireballSlot() < 0) {
            return false;
        }
        BedwarsMapCore core = baritone.getBedwarsArenaMemory() != null ? baritone.getBedwarsArenaMemory().getActiveCore() : null;
        double enemyVoidExp = core != null ? core.sampleVoidExposure(target.getBlockX(), target.getBlockZ()) : 0.0;
        boolean enemyHighBridge = target.getY() - ctx.player().getY() >= 2.5;
        if (enemyVoidExp < 0.35 && !enemyHighBridge) {
            return false;
        }
        if (!baritone.getInventoryBehavior().safeSelectHotbarSlot(loadout.fireballSlot())) {
            return false;
        }
        // Lead target feet along travel velocity so splash blows up the bridge or blasts them off!
        double flightTicks = Math.min(12.0, bodyDist / 1.45);
        Vec3 aimPt = new Vec3(
                target.getX() + enemyVel.x * flightTicks,
                target.getY() + 0.15,
                target.getZ() + enemyVel.z * flightTicks
        );
        Rotation aim = RotationUtils.calcRotationFromVec3d(eyePos, aimPt, ctx.playerRotations());
        baritone.getLookBehavior().setUrgentClutchMode(true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(aim, true, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
        baritone.getInputOverrideHandler().clearCombatKeysPreservingHysteresis();
        if (MouseModel.angularDistance(ctx.playerRotations(), aim) <= 8.0f) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            lastCombatFireballTick = tickCounter;
        }
        return true;
    }

    private boolean tryCombatThrowableOpener(LivingEntity target, Rotation desiredRot, double bodyDist) {
        if (bodyDist < 4.5 || bodyDist > 9.5 || target.hurtTime > 0 || tickCounter - lastThrowableTick < 28L || ctx.player() == null) {
            return false;
        }
        WarriorLoadout loadout = scanWarriorLoadout(ctx.player().getInventory());
        if (loadout.throwableSlot() < 0 || !baritone.getInventoryBehavior().safeSelectHotbarSlot(loadout.throwableSlot())) {
            return false;
        }
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(desiredRot, false, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
        baritone.getInputOverrideHandler().clearCombatKeysPreservingHysteresis();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        lastThrowableTick = tickCounter;
        return true;
    }

    private boolean tryVoidSaveEnderPearl() {
        if (ctx.player() == null || ctx.world() == null || ctx.player().onGround()
                || ctx.player().getDeltaMovement().y > -0.35 || ctx.player().fallDistance < 4.0f
                || tickCounter - lastVoidPearlTick < 40L) {
            return false;
        }
        BedwarsMapCore core = baritone.getBedwarsArenaMemory() != null ? baritone.getBedwarsArenaMemory().getActiveCore() : null;
        if (core == null) {
            return false;
        }
        int voidFloor = core.voidFloorY();
        if (ctx.player().getY() > voidFloor + 16.0) {
            // Only burn pearl if actually dropping into void (no floor under us within 8 blocks)
            BlockPos feet = ctx.playerFeet();
            for (int dy = 1; dy <= 8; dy++) {
                if (!ctx.world().getBlockState(feet.below(dy)).canBeReplaced()) {
                    return false;
                }
            }
        }
        WarriorLoadout loadout = scanWarriorLoadout(ctx.player().getInventory());
        if (loadout.pearlSlot() < 0) {
            return false;
        }
        // Aim pearl at home bed or nearest solid island block in [6..28]m
        Vec3 bestTarget = null;
        double bestDistSq = Double.POSITIVE_INFINITY;
        Vec3 eye = ctx.playerHead();
        BedPOI[] beds = core.getBedsSnapshot();
        for (int i = 0; i < beds.length; i++) {
            BedPOI b = beds[i];
            Vec3 c = new Vec3(b.x() + 0.5, b.y() + 1.0, b.z() + 0.5);
            double dSq = eye.distanceToSqr(c);
            if (dSq >= 25.0 && dSq <= 1024.0 && (b.isHome() ? dSq * 0.6 : dSq) < bestDistSq) {
                bestDistSq = b.isHome() ? dSq * 0.6 : dSq;
                bestTarget = c;
            }
        }
        if (bestTarget == null || !baritone.getInventoryBehavior().safeSelectHotbarSlot(loadout.pearlSlot())) {
            return false;
        }
        double dx = bestTarget.x - eye.x;
        double dy = bestTarget.y - eye.y;
        double dz = bestTarget.z - eye.z;
        double horiz = Math.hypot(dx, dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = computeBowPitchDegrees(horiz, dy, 1.50);
        Rotation pearlRot = new Rotation(yaw, Math.max(-42.0f, Math.min(10.0f, pitch)));
        baritone.getLookBehavior().setUrgentClutchMode(true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(pearlRot, true, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
        baritone.getInputOverrideHandler().clearAllKeys();
        if (MouseModel.angularDistance(ctx.playerRotations(), pearlRot) <= 12.0f) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            lastVoidPearlTick = tickCounter;
        }
        return true;
    }

    private boolean handleRangedBowFallback(LivingEntity target, Vec3 eyePos, double bodyDist) {
        WarriorLoadout loadout = scanWarriorLoadout(ctx.player().getInventory());
        if (loadout.bowSlot() < 0 || !loadout.hasArrows() || bodyDist < 5.5 || bodyDist > 26.0) {
            bowDrawTicks = 0;
            return false;
        }
        if (!baritone.getInventoryBehavior().safeSelectHotbarSlot(loadout.bowSlot())) {
            return false;
        }
        double dx = target.getX() - eyePos.x;
        double dy = (target.getY() + target.getBbHeight() * 0.55) - eyePos.y;
        double dz = target.getZ() - eyePos.z;
        double horiz = Math.hypot(dx, dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = computeBowPitchDegrees(horiz, dy, 2.85);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(new Rotation(yaw, pitch), false, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
        baritone.getInputOverrideHandler().clearAllKeys();
        if (bowDrawTicks < 21) {
            bowDrawTicks++;
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        } else {
            bowDrawTicks = 0;
        }
        return true;
    }

    private boolean handleFishingRodInterrupt(LivingEntity target, Rotation desiredRot, double bodyDist) {
        if (bodyDist < 3.8 || bodyDist > 7.2 || target.isBlocking() || tickCounter - lastRodUseTick < 24L) {
            return false;
        }
        WarriorLoadout loadout = scanWarriorLoadout(ctx.player().getInventory());
        if (loadout.rodSlot() < 0 || !baritone.getInventoryBehavior().safeSelectHotbarSlot(loadout.rodSlot())) {
            return false;
        }
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(desiredRot, false, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        lastRodUseTick = tickCounter;
        return true;
    }

    private void dispatchLegitAttackClick(LivingEntity target, CombatRuleset ruleset, double boxDist, Vec3 eyePos) {
        Minecraft mc = ctx.minecraft();
        if (mc == null || (mc.gui != null && mc.gui.screen() != null) || mc.isPaused()) {
            return;
        }
        if (mc.hitResult instanceof BlockHitResult) {
            return;
        }
        if (ctx.player() != null && boxDist <= ATTACK_REACH) {
            attackStrengthSum += ctx.player().getAttackStrengthScale(0.0f);
            swingsRecorded++;
            combatHitCount++;
        }
        InputOverrideHandler io = baritone.getInputOverrideHandler();
        if (io.getBlockBreakHelper().isHitting() || !io.canSwitchFromBreakToAttack()) {
            io.getBlockBreakHelper().stopBreakingBlock();
        }
        io.setInputForceState(Input.CLICK_LEFT, false);
        io.setInputForceState(Input.CLICK_RIGHT, false);
        if (mc.options != null && mc.options.keyUse != null) {
            mc.options.keyUse.setDown(false);
        }
        KeyMapping.click(resolveAttackKey(mc));
        io.notifyAttackExecuted();
        // Zero-RTT attack-synced W-tap / S-tap on fresh 1.8 damageable hit (no waiting 100ms for hurtTime packet!)
        if (ruleset == CombatRuleset.CLASSIC_1_8 && target != null && target.hurtTime <= 1 && boxDist <= 3.05) {
            if (boxDist < 2.20) {
                sTapTicks = Math.max(sTapTicks, 2);
            } else {
                wTapTicks = Math.max(wTapTicks, 2);
            }
        }
        recordBlackboxPvpEvent("ATTACK_FIRE",
                "ruleset=" + ruleset.name()
                        + ",dist=" + String.format("%.2f", boxDist)
                        + ",hitResult=" + (mc.hitResult != null ? mc.hitResult.getType().name() : "NULL")
                        + ",cd=" + String.format("%.2f", ctx.player().getAttackStrengthScale(0.5f))
                        + ",targetHurt=" + target.hurtTime);
    }

    /**
     * Checks whether the client crosshair points directly at an expected entity (or acceptable target).
     */
    public static boolean isTargetEntityOnCrosshair(HitResult hitResult, Entity target, Predicate<Entity> extraFilter) {
        if (!(hitResult instanceof EntityHitResult ehr) || ehr.getEntity() == null || !ehr.getEntity().isAlive()) {
            return false;
        }
        Entity hit = ehr.getEntity();
        return hit == target || (hit instanceof LivingEntity && extraFilter != null && extraFilter.test(hit));
    }

    /**
     * Enforces strict vanilla crosshair targeting: live clients never swing on {@link BlockHitResult} or {@code MISS}.
     */
    public static boolean canDispatchLegitAttack(
            HitResult hitResult,
            Entity target,
            Predicate<Entity> extraFilter,
            double boxDist,
            double maxReach,
            boolean crosshairAligned,
            boolean blockOccluded
    ) {
        if (boxDist > maxReach) {
            return false;
        }
        if (hitResult instanceof BlockHitResult) {
            return false;
        }
        if (hitResult instanceof EntityHitResult) {
            return isTargetEntityOnCrosshair(hitResult, target, extraFilter);
        }
        if (hitResult != null) {
            return false;
        }
        return !blockOccluded && crosshairAligned;
    }

    /**
     * Caps 1.8 click-burst density so three consecutive 1-tick (20 TPS) swings never occur back-to-back.
     */
    public static boolean isClassicBurstAllowed(long tickCounter, long lastAttackTick, long prevIntervalTicks) {
        long dt = tickCounter - lastAttackTick;
        if (dt <= 0L) {
            return false;
        }
        return !(dt == 1L && prevIntervalTicks <= 1L);
    }

    /**
     * Enforces minimum attack spacing; in strict legit mode (humanMode / BedWars) requires {@code dt >= 2} ticks
     * so swings never exceed 10 Hz / trigger 20 TPS burst checks.
     */
    public static boolean isClassicSpacingAllowed(
            long tickCounter,
            long lastAttackTick,
            long prevIntervalTicks,
            boolean strictLegitSpacing
    ) {
        if (!isClassicBurstAllowed(tickCounter, lastAttackTick, prevIntervalTicks)) {
            return false;
        }
        if (strictLegitSpacing && lastAttackTick >= 0L && (tickCounter - lastAttackTick) < 2L) {
            return false;
        }
        return true;
    }

    /**
     * Presses the real attack keybind only when no screen is open and the crosshair hits {@code expectedTarget}.
     */
    public static boolean clickAttackIfCrosshairMatches(IPlayerContext ctx, Entity expectedTarget, double maxDistSq) {
        if (ctx == null || expectedTarget == null || ctx.player() == null || ctx.player().distanceToSqr(expectedTarget) > maxDistSq) {
            return false;
        }
        Minecraft mc = ctx.minecraft();
        if (mc == null || (mc.gui != null && mc.gui.screen() != null) || mc.isPaused()) {
            return false;
        }
        if (!(mc.hitResult instanceof EntityHitResult ehr) || ehr.getEntity() != expectedTarget) {
            return false;
        }
        KeyMapping.click(resolveAttackKey(mc));
        return true;
    }

    /**
     * Presses the real use keybind only when no screen is open and the crosshair hits {@code expectedTarget}
     * (or an adjacent NPC hitbox proxy when interacting with Shop holograms).
     */
    public static boolean clickUseIfCrosshairMatches(IPlayerContext ctx, Entity expectedTarget, double maxDistSq, boolean allowNearbyProxy) {
        if (ctx == null || expectedTarget == null || ctx.player() == null || ctx.player().distanceToSqr(expectedTarget) > maxDistSq) {
            return false;
        }
        Minecraft mc = ctx.minecraft();
        if (mc == null || (mc.gui != null && mc.gui.screen() != null) || mc.isPaused()) {
            return false;
        }
        if (!(mc.hitResult instanceof EntityHitResult ehr) || ehr.getEntity() == null) {
            return false;
        }
        Entity hit = ehr.getEntity();
        if (hit != expectedTarget && (!allowNearbyProxy || hit.distanceToSqr(expectedTarget) > 1.5)) {
            return false;
        }
        KeyMapping.click(resolveUseKey(mc));
        return true;
    }

    public static InputConstants.Key resolveAttackKey(Minecraft mc) {
        try {
            return InputConstants.getKey(mc.options.keyAttack.saveString());
        } catch (Exception ignored) {
            return mc.options.keyAttack.getDefaultKey();
        }
    }

    public static InputConstants.Key resolveUseKey(Minecraft mc) {
        try {
            return InputConstants.getKey(mc.options.keyUse.saveString());
        } catch (Exception ignored) {
            return mc.options.keyUse.getDefaultKey();
        }
    }

    private Vec3 computeEffectiveEyePos(CombatRuleset ruleset) {
        if (ctx.player() == null) {
            return ctx.playerHead();
        }
        if (ruleset == CombatRuleset.CLASSIC_1_8
                && Baritone.settings().bedwarsMode.value
                && ctx.player().isCrouching()) {
            return new Vec3(ctx.player().getX(), ctx.player().getY() + 1.54, ctx.player().getZ());
        }
        return ctx.playerHead();
    }

    private void recordBlackboxPvpEvent(String type, String detail) {
        if (baritone instanceof Baritone b && b.getBedwarsBlackboxRecorder() != null) {
            b.getBedwarsBlackboxRecorder().recordEvent(type, detail);
        }
    }

    private boolean tryReactiveVoidClutch() {
        BedwarsProcess bws = baritone.getBedwarsProcess();
        return bws != null && bws.stepReactiveVoidClutch(false);
    }

    private boolean tryAntiFireballDeflect(List<LivingEntity> enemyCandidates) {
        if (ctx.player() == null || ctx.world() == null || tickCounter - lastFireballPunchTick < 2L) {
            return false;
        }
        Vec3 eyePos = ctx.playerHead();
        Vec3 pos = ctx.player().position();
        boolean hasNearestEnemy = false;
        double ex = 0.0, ey = 0.0, ez = 0.0;
        double bestEnemyDist = Double.MAX_VALUE;
        for (LivingEntity le : enemyCandidates) {
            double d = le.distanceToSqr(ctx.player());
            if (d < bestEnemyDist) {
                bestEnemyDist = d;
                ex = le.getX();
                ey = le.getEyeY();
                ez = le.getZ();
                hasNearestEnemy = true;
            }
        }

        FireballThreat bestThreat = null;
        Entity bestEntity = null;
        List<Entity> fireballs = baritone.getBedwarsArenaMemory().getTickContext().fireballs();
        for (int i = 0, n = fireballs.size(); i < n; i++) {
            Entity e = fireballs.get(i);
            Vec3 fv = e.getDeltaMovement();
            FireballThreat threat = BedwarsTacticalHelper.evaluateIncomingFireball(
                    e.getX(), e.getY(), e.getZ(),
                    fv.x, fv.y, fv.z,
                    pos.x, eyePos.y, pos.z,
                    ex, ey, ez, hasNearestEnemy
            );
            if (threat != null && (bestThreat == null || threat.dist() < bestThreat.dist())) {
                bestThreat = threat;
                bestEntity = e;
            }
        }
        if (bestThreat == null || bestEntity == null) {
            return false;
        }
        Rotation punchRot = new Rotation(bestThreat.punchYaw(), bestThreat.punchPitch());
        baritone.getLookBehavior().setUrgentClutchMode(true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(punchRot, false, true);
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).applyClientPreTickRotationAndRepick();
        baritone.getInputOverrideHandler().clearAllKeys();
        Minecraft mc = ctx.minecraft();
        boolean entityOnCrosshair = mc != null && (mc.gui == null || mc.gui.screen() == null) && !mc.isPaused()
                && mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() == bestEntity;
        if (bestThreat.dist() <= 4.35 && entityOnCrosshair) {
            KeyMapping.click(resolveAttackKey(mc));
            lastFireballPunchTick = tickCounter;
            recordBlackboxPvpEvent("ANTI_FIREBALL",
                    "dist=" + String.format("%.2f", bestThreat.dist()) + ",mode=" + bestThreat.aimMode());
        }
        return true;
    }

    private final BlockPos.MutableBlockPos jumpCeilingPos = new BlockPos.MutableBlockPos();

    private boolean hasCombatJumpCeilingClearance(float headingYawDeg) {
        BedwarsArenaMemory memory = baritone.getBedwarsArenaMemory();
        BedwarsMapCore core = Baritone.settings().bedwarsMode.value ? memory.getActiveCore() : null;
        BlockPos.MutableBlockPos scratch = core != null ? memory.getTickContext().scratchPos() : jumpCeilingPos;
        Vec3 pos = ctx.player().position();
        double heading = Math.toRadians(headingYawDeg);
        int y = Mth.floor(pos.y) + 2;
        int x = Mth.floor(pos.x);
        int z = Mth.floor(pos.z);
        int aheadX = Mth.floor(pos.x - Math.sin(heading));
        int aheadZ = Mth.floor(pos.z + Math.cos(heading));
        return isCombatCeilingClear(core, scratch, x, y, z)
                && ((x == aheadX && z == aheadZ) || isCombatCeilingClear(core, scratch, aheadX, y, aheadZ));
    }

    private boolean isCombatCeilingClear(BedwarsMapCore core, BlockPos.MutableBlockPos scratch, int x, int y, int z) {
        if (core != null && core.isChunkScanned(x >> 4, z >> 4)) {
            if (core.isKnownSolidBitset(x, y, z)) {
                return false;
            }
            if (core.isKnownAirInScannedChunk(x, y, z)) {
                return true;
            }
        }
        scratch.set(x, y, z);
        return ctx.world().getBlockState(scratch).getCollisionShape(ctx.world(), scratch).isEmpty();
    }

    public void resetTelemetry() {
        this.swingsRecorded = 0;
        this.attackStrengthSum = 0.0;
    }

    public void setBrainCombatMode(CombatMode mode) {
        this.brainCombatMode = mode != null ? mode : CombatMode.BALANCED;
    }

    public CombatMode getBrainCombatMode() {
        return brainCombatMode;
    }

    public int getSwingsRecorded() {
        return swingsRecorded;
    }

    public double getMeanAttackStrengthAtSwing() {
        return swingsRecorded == 0 ? 0.0 : (attackStrengthSum / swingsRecorded);
    }

    private static final Set<String> RANGED_THREAT_IDS = Set.of(
            "skeleton", "stray", "bogged", "pillager", "witch", "blaze", "ghast", "breeze"
    );

    private static boolean isRangedThreatType(LivingEntity living) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()).getPath();
        return RANGED_THREAT_IDS.contains(id);
    }

    private void collectTargets(List<LivingEntity> candidates, List<ThreatSnapshot> threats) {
        Predicate<Entity> filter = this.targetFilter;
        if (filter == null) {
            return;
        }
        Vec3 eyePos = ctx.playerHead();
        Vec3 playerPos = ctx.player().position();
        Rotation currentRot = ctx.playerRotations();
        double maxRange = 28.0;
        double blindRange = Baritone.settings().allowBreak.value ? 18.0 : 14.0;

        for (Entity entity : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (living.equals(ctx.player()) || !living.isAlive() || living.isRemoved()) {
                continue;
            }
            if (!filter.test(living)) {
                continue;
            }
            double bodyDist = living.distanceTo(ctx.player());
            if (bodyDist > maxRange) {
                continue;
            }
            if (!ctx.player().hasLineOfSight(living) && bodyDist > blindRange) {
                continue;
            }

            double boxDist = distanceToAabb(eyePos, living.getBoundingBox());
            double dx = living.getX() - playerPos.x;
            double dz = living.getZ() - playerPos.z;
            double horizDist = Math.hypot(dx, dz);
            Vec3 vel = living.getDeltaMovement();
            double ux = horizDist > 1e-4 ? dx / horizDist : 0.0;
            double uz = horizDist > 1e-4 ? dz / horizDist : 0.0;
            double radialClosing = -(vel.x * ux + vel.z * uz);
            double gap = Math.max(0.0, boxDist - ATTACK_REACH);
            double timeToMelee = gap <= 0.0 ? 0.0 : (radialClosing > 0.02 ? Math.min(100.0, gap / radialClosing) : 100.0);
            float yawToThreat = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float absFlankDeg = Math.abs(Rotation.normalizeYaw(yawToThreat - currentRot.getYaw()));
            boolean creeperSwelling = living instanceof Creeper creeper
                    && (creeper.getSwellDir() > 0 || creeper.getSwelling(0.0f) > 0.20f);

            ToDoubleFunction<LivingEntity> priorityResolver = this.contextualPriorityResolver;
            double tacticalAdj = 0.0;
            if (priorityResolver != null) {
                double computed = priorityResolver.applyAsDouble(living);
                if (Double.isFinite(computed)) {
                    tacticalAdj = computed;
                }
            }
            candidates.add(living);
            threats.add(new ThreatSnapshot(
                    living.getId(),
                    living.getX(),
                    living.getZ(),
                    boxDist,
                    radialClosing,
                    timeToMelee,
                    absFlankDeg,
                    living.hurtTime,
                    living.getHealth(),
                    creeperSwelling,
                    isRangedThreatType(living),
                    tacticalAdj
            ));
        }
    }

    /**
     * Selects the highest-priority threat index with threat-peeling: when the current target is
     * knocked back ({@code hurtTime >= 5}), an unhurt incoming charger threatening melee range peels
     * focus automatically, while keeping slight hysteresis stickiness in steady 1v1 trades.
     */
    public static int selectPrimaryThreatIndex(List<ThreatSnapshot> threats, int currentPrimaryId) {
        if (threats == null || threats.isEmpty()) {
            return -1;
        }
        int bestIdx = 0;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < threats.size(); i++) {
            ThreatSnapshot t = threats.get(i);
            double score = t.boxDist() + t.absFlankDeg() * 0.015 + t.tacticalPriorityAdjustment();
            if (t.creeperSwelling() && t.boxDist() < 5.0) {
                score -= 3.5;
            }
            if (t.health() <= 6.0f && t.boxDist() < 4.5) {
                score -= 0.55;
            }
            if (t.hurtTime() >= 5) {
                score += 1.65 * (t.hurtTime() / 10.0);
            }
            if (t.hurtTime() <= 2 && (t.boxDist() <= 3.8 || t.timeToMeleeTicks() <= 12.0)) {
                score -= Math.min(1.6, Math.max(0.0, t.radialClosingSpeed()) * 5.0);
                if (t.timeToMeleeTicks() <= 12.0) {
                    score -= 0.90 * (1.0 - t.timeToMeleeTicks() / 12.0);
                }
                if (t.boxDist() <= 3.3) {
                    score -= 0.65;
                }
            }
            if (t.id() == currentPrimaryId && t.hurtTime() <= 3) {
                score -= 0.45;
            }
            if (score < bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /**
     * Evaluates the 8 cardinal/diagonal headings relative to {@code desiredYaw} and picks the best
     * void-safe kiting heading that balances primary strike/cooldown range against repulsion from all
     * nearby threats. Returns {@code Float.NaN} if zero headings are safe.
     */
    public static float chooseBestKiteYaw(
            List<ThreatSnapshot> threats,
            int primaryIdx,
            double playerX,
            double playerZ,
            float desiredYaw,
            boolean wantAttackSpacing,
            boolean retreating,
            int preferredStrafeDir,
            boolean[] dirSafe8
    ) {
        if (threats == null || threats.isEmpty() || primaryIdx < 0 || primaryIdx >= threats.size()
                || dirSafe8 == null || dirSafe8.length < 8) {
            return Float.NaN;
        }
        ThreatSnapshot primary = threats.get(primaryIdx);
        double dPrimCur = Math.hypot(primary.x() - playerX, primary.z() - playerZ);
        double strafeRad = Math.toRadians(desiredYaw + (preferredStrafeDir >= 0 ? 90.0 : -90.0));
        double strafeX = -Math.sin(strafeRad);
        double strafeZ = Math.cos(strafeRad);

        float bestYaw = Float.NaN;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int dirIdx = 0; dirIdx < 8; dirIdx++) {
            if (!dirSafe8[dirIdx]) {
                continue;
            }
            float candYaw = Rotation.normalizeYaw(desiredYaw + dirIdx * 45.0f);
            double rad = Math.toRadians(candYaw);
            double stepX = -Math.sin(rad);
            double stepZ = Math.cos(rad);
            double projX = playerX + stepX * 0.85;
            double projZ = playerZ + stepZ * 0.85;

            double dPrimProj = Math.hypot(primary.x() - projX, primary.z() - projZ);
            double score;
            if (primary.creeperSwelling() || retreating) {
                score = (dPrimProj - dPrimCur) * 3.0 + Math.min(6.0, dPrimProj) * 0.45;
            } else {
                double ideal = wantAttackSpacing ? 3.30 : 2.60;
                score = -Math.abs(dPrimProj - ideal) * (wantAttackSpacing ? 2.2 : 2.4);
            }

            for (ThreatSnapshot t : threats) {
                double dCur = Math.hypot(t.x() - playerX, t.z() - playerZ);
                double dProj = Math.hypot(t.x() - projX, t.z() - projZ);
                if (t.creeperSwelling() && dProj < 4.8 && (t.id() != primary.id() || !retreating)) {
                    score -= (4.8 - dProj) * 5.0;
                } else if (t.id() != primary.id()) {
                    if (dProj < 2.65) {
                        score -= (2.65 - dProj) * 4.2;
                    }
                    if (dCur < 5.5) {
                        score += (dProj - dCur) * 2.4;
                    }
                }
            }

            if (!retreating && !primary.creeperSwelling()) {
                score += 0.42 * (stepX * strafeX + stepZ * strafeZ);
            }
            if (dirSafe8[(dirIdx + 7) & 7]) {
                score += 0.18;
            }
            if (dirSafe8[(dirIdx + 1) & 7]) {
                score += 0.18;
            }

            if (score > bestScore) {
                bestScore = score;
                bestYaw = candYaw;
            }
        }
        return bestYaw;
    }

    /**
     * Environment-aware 16-ray context kiting that balances threat range with active room/wall corner
     * escape, void-edge buffers, and anti-back-to-void knockback awareness.
     */
    public static float chooseContextKiteYaw16(
            List<ThreatSnapshot> threats,
            int primaryIdx,
            double playerX,
            double playerZ,
            float desiredYaw,
            Float prevKiteYaw,
            boolean wantAttackSpacing,
            boolean retreating,
            int preferredStrafeDir,
            RayEnv16 env
    ) {
        if (threats == null || threats.isEmpty() || primaryIdx < 0 || primaryIdx >= threats.size()
                || env == null || env.safe() == null || env.safe().length < 16) {
            return Float.NaN;
        }
        ThreatSnapshot primary = threats.get(primaryIdx);
        double dPrimCur = Math.hypot(primary.x() - playerX, primary.z() - playerZ);
        double awayX = playerX - primary.x();
        double awayZ = playerZ - primary.z();
        double awayLen = Math.hypot(awayX, awayZ);
        if (awayLen > 1e-4) {
            awayX /= awayLen;
            awayZ /= awayLen;
        }
        double strafeRad = Math.toRadians(desiredYaw + (preferredStrafeDir >= 0 ? 90.0 : -90.0));
        double strafeX = -Math.sin(strafeRad);
        double strafeZ = Math.cos(strafeRad);

        float bestYaw = Float.NaN;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int r = 0; r < 16; r++) {
            if (!env.safe()[r]) {
                continue;
            }
            float candYaw = Rotation.normalizeYaw(desiredYaw + r * 22.5f);
            double rad = Math.toRadians(candYaw);
            double stepX = -Math.sin(rad);
            double stepZ = Math.cos(rad);
            double projX = playerX + stepX * 0.85;
            double projZ = playerZ + stepZ * 0.85;

            double dPrimProj = Math.hypot(primary.x() - projX, primary.z() - projZ);
            double score;
            if (primary.creeperSwelling()) {
                score = (dPrimProj - dPrimCur) * 3.0 + Math.min(6.0, dPrimProj) * 0.45;
            } else if (retreating) {
                // Controlled brief disengage to ~2.85m while staying engaged on the edge of reach
                score = -Math.abs(dPrimProj - 2.85) * 1.9 + (dPrimProj - dPrimCur) * 1.2;
            } else {
                double ideal = primary.ranged() ? 2.15 : (wantAttackSpacing ? 2.88 : 2.62);
                score = -Math.abs(dPrimProj - ideal) * 2.55;
            }

            for (ThreatSnapshot t : threats) {
                double dCur = Math.hypot(t.x() - playerX, t.z() - playerZ);
                double dProj = Math.hypot(t.x() - projX, t.z() - projZ);
                if (t.creeperSwelling() && dProj < 4.8 && (t.id() != primary.id() || !retreating)) {
                    score -= (4.8 - dProj) * 5.0;
                } else if (t.id() != primary.id()) {
                    if (dProj < 2.25) {
                        score -= (2.25 - dProj) * 2.8;
                    }
                    if (dCur < 3.05) {
                        score += (dProj - dCur) * 0.75;
                    }
                }
            }

            double wDist = env.wallDist()[r];
            double vDist = env.voidDist()[r];
            if (wDist < 1.15) {
                score -= (1.15 - wDist) * 2.40;
            }
            score += Math.min(1.6, wDist) * 0.25;

            if (vDist < 2.35) {
                score -= (2.35 - vDist) * 2.15;
            }
            // Strongly discourage stepping onto a void edge aligned behind knockback from primary attacker
            double stepDotAway = stepX * awayX + stepZ * awayZ;
            if (stepDotAway > 0.20 && vDist < 2.45) {
                score -= (2.45 - vDist) * (2.6 + 2.2 * stepDotAway);
            }
            // Avoid backing straight into a tight room/corner wall while trading
            if (stepDotAway > 0.25 && wDist < 1.05) {
                score -= (1.05 - wDist) * 1.40;
            }

            if (env.safe()[(r + 15) & 15]) {
                score += 0.22;
            }
            if (env.safe()[(r + 1) & 15]) {
                score += 0.22;
            }
            // Prefer footholds with solid ground directly behind the step so knockback won't ring-out us
            score += Math.min(2.4, env.voidDist()[(r + 8) & 15]) * 0.16;
            if (!primary.creeperSwelling() && preferredStrafeDir != 0) {
                double strafeWeight = primary.ranged() ? 0.95 : (retreating ? 0.55 : 0.78);
                score += strafeWeight * (stepX * strafeX + stepZ * strafeZ);
            }
            if (prevKiteYaw != null) {
                score += 0.42 * Math.cos(Math.toRadians(Rotation.normalizeYaw(candYaw - prevKiteYaw)));
            }

            if (score > bestScore) {
                bestScore = score;
                bestYaw = candYaw;
            }
        }
        return bestYaw;
    }

    private static boolean hasCloseSecondaryThreat(List<ThreatSnapshot> threats, int primaryIdx, double maxBoxDist) {
        int primaryId = threats.get(primaryIdx).id();
        for (ThreatSnapshot t : threats) {
            if (t.id() != primaryId && t.boxDist() <= maxBoxDist) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUrgentSecondaryCharger(
            List<ThreatSnapshot> threats,
            int primaryIdx,
            double maxBoxDist,
            double maxTimeToMelee
    ) {
        int primaryId = threats.get(primaryIdx).id();
        for (ThreatSnapshot t : threats) {
            if (t.id() != primaryId && (t.boxDist() <= maxBoxDist || t.timeToMeleeTicks() <= maxTimeToMelee)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasThreatInCone(
            List<ThreatSnapshot> threats,
            int primaryIdx,
            double playerX,
            double playerZ,
            double checkYawDeg,
            double maxDist
    ) {
        double rad = Math.toRadians(checkYawDeg);
        double lookX = -Math.sin(rad);
        double lookZ = Math.cos(rad);
        int primaryId = threats.get(primaryIdx).id();
        for (ThreatSnapshot t : threats) {
            if (t.id() == primaryId) {
                continue;
            }
            double dx = t.x() - playerX;
            double dz = t.z() - playerZ;
            double dist = Math.hypot(dx, dz);
            if (dist <= maxDist && dist > 1e-4 && ((dx / dist) * lookX + (dz / dist) * lookZ) > 0.55) {
                return true;
            }
        }
        return false;
    }

    private static double distanceToAabb(Vec3 point, AABB box) {
        double cx = Math.max(box.minX, Math.min(box.maxX, point.x));
        double cy = Math.max(box.minY, Math.min(box.maxY, point.y));
        double cz = Math.max(box.minZ, Math.min(box.maxZ, point.z));
        return point.distanceTo(new Vec3(cx, cy, cz));
    }

    private RayEnv16 probeEnvironment16(float baseYaw) {
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        boolean[] safe = new boolean[16];
        double[] wallDist = new double[16];
        double[] voidDist = new double[16];
        Vec3 pos = ctx.player().position();
        Vec3 vel = ctx.player().getDeltaMovement();
        int by = ctx.playerFeet().y;
        double[] radii = {0.48, 0.92, 1.38, 1.95, 2.55, 3.3, 4.2};

        for (int rIdx = 0; rIdx < 16; rIdx++) {
            double yawRad = Math.toRadians(baseYaw + rIdx * 22.5f);
            double dirX = -Math.sin(yawRad);
            double dirZ = Math.cos(yawRad);
            double wDist = 2.65;
            double vDist = 4.65;

            for (double rad : radii) {
                double leadScale = rad <= 1.38 ? 1.4 : 0.6;
                double sampleX = pos.x + dirX * rad + vel.x * leadScale;
                double sampleZ = pos.z + dirZ * rad + vel.z * leadScale;
                int bx = (int) Math.floor(sampleX);
                int bz = (int) Math.floor(sampleZ);

                if (wDist > 2.60 && rad <= 2.55) {
                    if (!MovementHelper.canWalkThrough(bsi, bx, by, bz)
                            || !MovementHelper.canWalkThrough(bsi, bx, by + 1, bz)) {
                        wDist = rad;
                    }
                }
                if (vDist > 4.60) {
                    if (bsi.get0(bx, by, bz).getBlock() instanceof LiquidBlock) {
                        vDist = rad;
                    } else {
                        boolean hasFloor = false;
                        for (int dy = 1; dy <= 2; dy++) {
                            if (MovementHelper.canWalkOn(bsi, bx, by - dy, bz)) {
                                hasFloor = true;
                                break;
                            }
                        }
                        if (!hasFloor) {
                            vDist = rad;
                        }
                    }
                }
            }
            wallDist[rIdx] = wDist;
            voidDist[rIdx] = vDist;
            safe[rIdx] = wDist > 0.72 && vDist > 1.38;
        }
        return new RayEnv16(safe, wallDist, voidDist);
    }

    /**
     * Computes the closest point from {@code eyePos} onto {@code targetBox} after shrinking the box
     * horizontally/vertically by {@code shrinkRatio} (preventing robotic extreme edge-pixel locking).
     */
    public static Vec3 computeShrunkTargetPoint(Vec3 eyePos, AABB targetBox, double shrinkRatio) {
        double dx = (targetBox.maxX - targetBox.minX) * 0.5 * Math.min(0.45, Math.max(0.0, shrinkRatio));
        double dy = (targetBox.maxY - targetBox.minY) * 0.5 * Math.min(0.45, Math.max(0.0, shrinkRatio));
        double dz = (targetBox.maxZ - targetBox.minZ) * 0.5 * Math.min(0.45, Math.max(0.0, shrinkRatio));

        double minX = targetBox.minX + dx;
        double maxX = targetBox.maxX - dx;
        double minY = targetBox.minY + dy;
        double maxY = targetBox.maxY - dy;
        double minZ = targetBox.minZ + dz;
        double maxZ = targetBox.maxZ - dz;

        double cx = Math.max(minX, Math.min(maxX, eyePos.x));
        double cy = Math.max(minY + (maxY - minY) * 0.25, Math.min(maxY, eyePos.y));
        double cz = Math.max(minZ, Math.min(maxZ, eyePos.z));
        return new Vec3(cx, cy, cz);
    }

    public static boolean isCrosshairOnTarget(Vec3 eyePos, AABB targetBox, Rotation currentRot, double maxDist) {
        Vec3 lookDir = RotationUtils.calcLookDirectionFromRotation(currentRot);
        Vec3 end = eyePos.add(lookDir.scale(maxDist));
        return targetBox.clip(eyePos, end).isPresent();
    }
}
