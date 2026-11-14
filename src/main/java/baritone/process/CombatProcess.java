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
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.bedwars.BedwarsProcess;
import baritone.bedwars.BedwarsTacticalHelper;
import baritone.bedwars.BedwarsTacticalHelper.ClutchCandidate;
import baritone.bedwars.BedwarsTacticalHelper.FireballThreat;
import baritone.behavior.look.ForkableRandom;
import baritone.brain.BrainActionSpace.CombatMode;
import baritone.human.HumanProfile;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import baritone.utils.InputOverrideHandler;
import baritone.utils.accessor.IMinecraft;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
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
            boolean ranged
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
            this(id, x, z, boxDist, radialClosingSpeed, timeToMeleeTicks, absFlankDeg, hurtTime, health, creeperSwelling, false);
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

    private static final double ENGAGE_DIRECT_RANGE = 4.35;
    private static final double ENGAGE_CHASE_MAX_RANGE = 12.50;
    private static final double ATTACK_REACH = 2.98;
    private static final double HITBOX_SHRINK_RATIO = 0.16;

    private final ForkableRandom rand;
    private final Map<Integer, Long> targetReadyTick = new HashMap<>();

    private Predicate<Entity> targetFilter;
    private long tickCounter = 0L;
    private long lastCombatTick = -100L;
    private int currentPrimaryId = -1;
    private transient LivingEntity activeTargetEntity = null;
    private Float prevKiteYaw = null;
    private BlockPos lastPathGoalPos = null;
    private boolean prevCommandWasPath = false;
    private boolean prevGoalExact = false;
    private int strafeDir = 1;
    private int strafeSwitchTicks = 10;
    private int strafeFlipCooldown = 0;
    private int wTapTicks = 0;
    private int sTapTicks = 0;
    private int critHoldTicks = 0;
    private int blockHitTicks = 0;
    private int bowDrawTicks = 0;
    private long lastRodUseTick = -100L;
    private double clickAccumulator = 0.0;
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
    private int clutchChainTicks = 0;
    private int clutchStartY = Integer.MIN_VALUE;
    private long lastFireballPunchTick = -100L;
    private int swingsRecorded = 0;
    private double attackStrengthSum = 0.0;
    private int combatHitCount = 0;
    private volatile int autoCancelAfterClearTicks = 0;
    private int consecutiveEmptyTicks = 0;
    private volatile CombatMode brainCombatMode = CombatMode.BALANCED;

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
        this.currentPrimaryId = -1;
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
        this.lastSpacedTargetId = -1;
        this.lastSpacedTargetHurtTime = 0;
        this.lastJumpResetPlayerHurtTime = 0;
        this.hitSelectWaitTicks = 0;
        this.hitSelectLogEmitted = false;
        this.shieldAxeDelayTicks = 0;
        this.shieldSwapBackDelayTicks = 0;
        this.shieldReturnSwordSlot = -1;
        this.clutchChainTicks = 0;
        this.clutchStartY = Integer.MIN_VALUE;
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
        this.currentPrimaryId = -1;
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
        this.lastSpacedTargetId = -1;
        this.lastSpacedTargetHurtTime = 0;
        this.lastJumpResetPlayerHurtTime = 0;
        this.hitSelectWaitTicks = 0;
        this.hitSelectLogEmitted = false;
        this.shieldAxeDelayTicks = 0;
        this.shieldSwapBackDelayTicks = 0;
        this.shieldReturnSwordSlot = -1;
        this.clutchChainTicks = 0;
        this.clutchStartY = Integer.MIN_VALUE;
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
        if (baritone.getSurvivalBehavior() != null && baritone.getSurvivalBehavior().isUsingSurvivalReflex()) {
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

        List<LivingEntity> candidates = new ArrayList<>();
        List<ThreatSnapshot> threats = new ArrayList<>();
        collectTargets(candidates, threats);
        if (Baritone.settings().bedwarsMode.value && tryAntiFireballDeflect(candidates)) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        if (threats.isEmpty()) {
            this.currentPrimaryId = -1;
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

        int primaryIdx = selectPrimaryThreatIndex(threats, currentPrimaryId);
        LivingEntity target = candidates.get(primaryIdx);
        ThreatSnapshot primaryThreat = threats.get(primaryIdx);
        if (this.currentPrimaryId != target.getId()) {
            this.hitSelectWaitTicks = 0;
            this.hitSelectLogEmitted = false;
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
        Vec3 relVel = target.getDeltaMovement().subtract(ctx.player().getDeltaMovement());
        Vec3 aimPoint = computeReachOptimalAimPoint(eyePos, target.getBoundingBox(), relVel, 1.0, HITBOX_SHRINK_RATIO);
        double boxDist = distanceToAabb(eyePos, target.getBoundingBox());
        double bodyDist = ctx.player().distanceTo(target);

        Rotation desiredRot = RotationUtils.calcRotationFromVec3d(eyePos, aimPoint, ctx.playerRotations());
        boolean hasLos = ctx.player().hasLineOfSight(target);
        RayEnv16 env16 = probeEnvironment16(desiredRot.getYaw());

        if (!hasLos) {
            boolean closeUnblockedThreat = hasCloseUnblockedThreat(candidates, 2.35);
            if (!closeUnblockedThreat && tryDirectBarrierBreach(eyePos, target, desiredRot.getYaw(), true, bodyDist)) {
                this.prevCommandWasPath = false;
                this.lastPathGoalPos = null;
                this.prevGoalExact = false;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            prevKiteYaw = null;
            return makeCombatPathCommand(target.blockPosition(), true);
        }

        if (Baritone.settings().allowBreak.value
                && bodyDist <= 4.35
                && (!env16.safe()[0] || boxDist > ATTACK_REACH - 0.15)
                && !hasCloseUnblockedThreat(candidates, 2.40)
                && tryDirectBarrierBreach(eyePos, target, desiredRot.getYaw(), false, bodyDist)) {
            this.prevCommandWasPath = false;
            this.lastPathGoalPos = null;
            this.prevGoalExact = false;
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (bodyDist > ENGAGE_DIRECT_RANGE) {
            if (hasLos && handleRangedBowFallback(target, eyePos, bodyDist)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (hasLos && handleFishingRodInterrupt(target, desiredRot, bodyDist)) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            if (bodyDist <= ENGAGE_CHASE_MAX_RANGE && env16.safe()[0] && env16.voidDist()[0] >= 1.95) {
                this.prevCommandWasPath = false;
                this.lastPathGoalPos = null;
                this.prevGoalExact = false;
                ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(desiredRot, false, true);
                baritone.getInputOverrideHandler().clearAllKeys();
                float chaseYaw = chooseContextKiteYaw16(
                        threats, primaryIdx, ctx.player().position().x, ctx.player().position().z,
                        desiredRot.getYaw(), prevKiteYaw, false, false, strafeDir, env16);
                float moveYaw = Float.isNaN(chaseYaw) ? desiredRot.getYaw() : chaseYaw;
                prevKiteYaw = moveYaw;
                baritone.getInputOverrideHandler().setDesiredMovementYaw(moveYaw);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
                if (bodyDist >= 5.8 && ctx.player().onGround() && env16.safe()[0] && env16.voidDist()[0] >= 3.0) {
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
        baritone.getInputOverrideHandler().clearAllKeys();
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
                selectOptimalMeleeWeapon(target, boxDist, loadout);
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
            if (distanceToAabb(eyePos, e.getBoundingBox()) <= maxBoxDist && ctx.player().hasLineOfSight(e)) {
                return true;
            }
        }
        return false;
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
        boolean opponentAimingClose = target != null && isOpponentAimingAtUs(target, ctx.player(), 12.0f);
        if (--strafeSwitchTicks <= 0 || (opponentAimingClose && strafeFlipCooldown <= 0 && boxDist <= 3.35)) {
            strafeDir = -strafeDir;
            strafeFlipCooldown = 4;
            // Mixed short (4-7t) & medium (9-15t) durations give natural CV > 15% without robotic square-wave strafes
            strafeSwitchTicks = rand.nextDouble() < 0.45
                    ? (4 + (int) (rand.nextDouble() * 4.0))
                    : (9 + (int) (rand.nextDouble() * 7.0));
        }

        ThreatSnapshot primary = threats.get(primaryIdx);
        Vec3 pos = ctx.player().position();
        boolean openFooting = env.safe()[0] && env.safe()[4] && env.safe()[8] && env.safe()[12];
        boolean safeBackward = env.safe()[8]
                && env.wallDist()[8] >= 1.95
                && env.voidDist()[8] >= 2.35
                && !hasThreatInCone(threats, primaryIdx, pos.x, pos.z, desiredYaw + 180.0f, 3.4);

        // Edge-safe strafe guard: never circle-strafe off a 2.5m void cliff / bridge edge
        int preferredStrafe = strafeDir;
        int activeLatIdx = strafeDir >= 0 ? 4 : 12;
        int oppLatIdx = strafeDir >= 0 ? 12 : 4;
        if (env.voidDist()[activeLatIdx] < 2.50 || !env.safe()[activeLatIdx]) {
            if (env.voidDist()[oppLatIdx] >= 2.50 && env.safe()[oppLatIdx]) {
                strafeDir = -strafeDir;
                preferredStrafe = strafeDir;
            } else {
                preferredStrafe = 0;
            }
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
                sTapTicks = boxDist < 2.10 ? 3 : 2;
                recordBlackboxPvpEvent("STAP", "dist=" + String.format("%.2f", boxDist) + ",ticks=" + sTapTicks);
            } else if (resetAction == SpacingAction.W_TAP) {
                wTapTicks = 2;
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
                    && rand.nextDouble() < 0.85) {
                sTapTicks = 0;
                wTapTicks = 0;
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
                didJumpReset = true;
                recordBlackboxPvpEvent("JUMPRESET", "hurtTime=" + pHurt + ",backVoid=" + String.format("%.2f", backVoidDist));
            }
        }
        lastJumpResetPlayerHurtTime = pHurt;

        if (!didJumpReset && sTapTicks > 0) {
            sTapTicks--;
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            if (safeBackward) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            }
            return openFooting;
        }
        if (!didJumpReset && wTapTicks > 0) {
            wTapTicks--;
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            return openFooting;
        }

        boolean primaryRanged = primary.ranged();
        boolean urgentSecondary = hasUrgentSecondaryCharger(threats, primaryIdx, 3.6, 8.0);
        boolean defensiveKite = brainCombatMode == CombatMode.DEFENSIVE_KITE;
        boolean wantAttackSpacing = !primaryRanged && (
                defensiveKite
                        || (ruleset == CombatRuleset.MODERN_COOLDOWN && cooldown < 0.85f && boxDist < 2.95)
                        || (threats.size() >= 2 && urgentSecondary && boxDist < 2.65 && cooldown < 0.85f)
        );
        boolean retreating = !primaryRanged
                && (((urgentSecondary && boxDist < 2.35) || boxDist < 2.28)
                    && ruleset == CombatRuleset.MODERN_COOLDOWN
                    && cooldown < 0.85f
                || (defensiveKite && boxDist < 2.75 && cooldown < 0.90f));
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
            boolean closingForward = moveRelYaw <= 56.0f && boxDist > (primaryRanged ? 2.25 : 2.35);
            boolean critDescending = ruleset == CombatRuleset.MODERN_COOLDOWN && !ctx.player().onGround();
            boolean allowSprint = closingForward && !wantAttackSpacing && !retreating && !critDescending
                    && (ruleset == CombatRuleset.CLASSIC_1_8 || boxDist > 2.68 || (primaryRanged && !urgentSecondary && cooldown >= 0.86f));
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
            boolean canSwordBlockHit = mainHand.get(DataComponents.BLOCKS_ATTACKS) != null
                    || (isSwordItem(mainHand) && ctx.player().getItemBySlot(EquipmentSlot.OFFHAND).isEmpty());
            if (canSwordBlockHit) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
        }
        if (boxDist > 3.35) {
            clickAccumulator = Math.min(clickAccumulator, 0.35);
            hitSelectWaitTicks = 0;
            hitSelectLogEmitted = false;
            return;
        }
        // HitSelect gating on neutral ground exchange (bounded timeout prevents passive freezing)
        boolean shieldBreakReady = target.isBlocking() && loadout.axeSlot() >= 0;
        boolean hitSelectReady = shieldBreakReady || shouldHitSelectEngage(
                ctx.player().hurtTime,
                target.hurtTime,
                target.onGround(),
                boxDist,
                hitSelectWaitTicks,
                5
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

        // 10-14 CPS butterfly/jitter cadence using human variance
        double baseCps = Math.max(10.0, Math.min(14.0, profile.cpsMean() > 8.5 ? profile.cpsMean() : 11.8));
        double jitterCps = baseCps * (0.85 + rand.nextDouble() * 0.30);
        clickAccumulator += jitterCps / 20.0;
        if (clickAccumulator < 1.0) {
            return;
        }
        clickAccumulator -= 1.0;

        InputOverrideHandler io = baritone.getInputOverrideHandler();
        if (io.getBlockBreakHelper().isHitting() || !io.canSwitchFromBreakToAttack()) {
            io.getBlockBreakHelper().stopBreakingBlock();
            io.setInputForceState(Input.CLICK_LEFT, false);
            return;
        }
        Minecraft mc = ctx.minecraft();
        boolean blockedByBlock = mc.hitResult instanceof BlockHitResult bhr
                && eyePos.distanceTo(bhr.getLocation()) < boxDist - 0.15;
        if (blockedByBlock) {
            return;
        }
        boolean hitResultEntity = mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() == target;
        boolean swingAligned = hitResultEntity
                || (crosshairAligned && isCrosshairOnTarget(eyePos, target.getBoundingBox(), ctx.playerRotations(), 3.35));

        if (swingAligned && boxDist <= ATTACK_REACH) {
            dispatchLegitAttackClick(target, CombatRuleset.CLASSIC_1_8, boxDist, eyePos);
            ItemStack mainHand = ctx.player().getMainHandItem();
            boolean canSwordBlockHit = mainHand.get(DataComponents.BLOCKS_ATTACKS) != null
                    || (isSwordItem(mainHand) && ctx.player().getItemBySlot(EquipmentSlot.OFFHAND).isEmpty());
            if (canSwordBlockHit && boxDist <= 2.95) {
                blockHitTicks = 1;
            }
        } else if (boxDist <= 3.35 && swingAligned) {
            // Legit client pre-swing while closing inside 3.35m (vanilla click impulse, no bypass)
            dispatchLegitAttackClick(target, CombatRuleset.CLASSIC_1_8, boxDist, eyePos);
        }
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

        // Initiate jump crit cleanly right after stagger/knockback or high cooldown so we don't trade freely
        if (shouldJumpCritModern(allowJumpCrit, ctx.player().onGround(), cooldown, boxDist, target.hurtTime, target.isBlocking())) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            ctx.player().setSprinting(false);
        }

        // Hold up to 4 ticks mid-air for downward fall (vel.y < -0.04) & cancel sprint for a true 1.5x crit
        boolean airborne = !ctx.player().onGround();
        if (airborne && cooldown >= requiredCooldown && !axeBreakReady) {
            ctx.player().setSprinting(false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            if (ctx.player().getDeltaMovement().y >= -0.04 && boxDist <= 2.85 && critHoldTicks < 4) {
                critHoldTicks++;
                return;
            }
        }
        critHoldTicks = 0;

        Minecraft mc = ctx.minecraft();
        boolean hitResultEntity = mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() == target;
        boolean blockedByBlock = mc.hitResult instanceof BlockHitResult bhr
                && eyePos.distanceTo(bhr.getLocation()) < boxDist - 0.15;
        boolean raycastVerified = hitResultEntity || (!blockedByBlock && crosshairAligned && boxDist <= ATTACK_REACH);

        if (cooldown >= requiredCooldown && raycastVerified && boxDist <= ATTACK_REACH) {
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
     * Warrior gear helpers (1.8 blockhit / hit-select, Modern Axe-vs-Shield, Shield defense, Mace, Bow, Rod).
     */
    public record WarriorLoadout(int swordSlot, int axeSlot, int maceSlot, int bowSlot, int rodSlot, int shieldSlot, boolean offhandShield, boolean hasArrows) {
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
        if (boxDist <= 2.85 || (isVoidKnockback && boxDist <= 3.10) || (isClassic18 && boxDist <= 2.95)) {
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
     * Eye-level clamped reach-optimal aim point with 1-tick relative velocity lead and inset XZ bounds.
     * Eliminates diagonal pitch reach loss on flat ground while tracking elevation differences smoothly.
     */
    public static Vec3 computeReachOptimalAimPoint(
            Vec3 eyePos,
            AABB targetBox,
            Vec3 relVel,
            double leadTicks,
            double shrinkRatio
    ) {
        double lx = Math.max(-0.35, Math.min(0.35, relVel.x * leadTicks));
        double ly = Math.max(-0.18, Math.min(0.18, relVel.y * leadTicks));
        double lz = Math.max(-0.35, Math.min(0.35, relVel.z * leadTicks));
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
        return new Vec3(ax, ay, az);
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
        return new WarriorLoadout(swordSlot, axeSlot, maceSlot, bowSlot, rodSlot, shieldSlot, offhandShield, hasArrows);
    }

    private static boolean isSwordItem(ItemStack st) {
        if (st.isEmpty()) {
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        return path.endsWith("_sword");
    }

    private void selectOptimalMeleeWeapon(LivingEntity target, double boxDist, WarriorLoadout loadout) {
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
        if (loadout.swordSlot() >= 0) {
            baritone.getInventoryBehavior().safeSelectHotbarSlot(loadout.swordSlot());
        } else if (loadout.axeSlot() >= 0) {
            baritone.getInventoryBehavior().safeSelectHotbarSlot(loadout.axeSlot());
        }
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
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
        lastRodUseTick = tickCounter;
        return true;
    }

    private void dispatchLegitAttackClick(LivingEntity target, CombatRuleset ruleset, double boxDist, Vec3 eyePos) {
        Minecraft mc = ctx.minecraft();
        if (ctx.player() != null) {
            if (ruleset == CombatRuleset.MODERN_COOLDOWN && !ctx.player().onGround()) {
                ctx.player().setSprinting(false);
            }
            if (boxDist <= ATTACK_REACH) {
                attackStrengthSum += ctx.player().getAttackStrengthScale(0.0f);
                swingsRecorded++;
                combatHitCount++;
            }
        }
        if (ruleset == CombatRuleset.CLASSIC_1_8 && Baritone.settings().bedwarsMode.value) {
            // Clear vanilla post-1.9 client missTime penalty for 1.8 Hypixel Bedwars hit registration
            ((IMinecraft) mc).pathadol$setMissTime(0);
        }
        InputOverrideHandler io = baritone.getInputOverrideHandler();
        if (io.getBlockBreakHelper().isHitting() || !io.canSwitchFromBreakToAttack()) {
            io.getBlockBreakHelper().stopBreakingBlock();
        }
        io.setInputForceState(Input.CLICK_LEFT, false);
        KeyMapping.click(resolveAttackKey(mc));
        io.notifyAttackExecuted();
        recordBlackboxPvpEvent("ATTACK_FIRE",
                "ruleset=" + ruleset.name()
                        + ",dist=" + String.format("%.2f", boxDist)
                        + ",hitResult=" + (mc.hitResult != null ? mc.hitResult.getType().name() : "NULL")
                        + ",cd=" + String.format("%.2f", ctx.player().getAttackStrengthScale(0.5f))
                        + ",targetHurt=" + target.hurtTime);
    }

    private static InputConstants.Key resolveAttackKey(Minecraft mc) {
        try {
            return InputConstants.getKey(mc.options.keyAttack.saveString());
        } catch (Exception ignored) {
            return mc.options.keyAttack.getDefaultKey();
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
        if (ctx.player() == null || ctx.world() == null) {
            return false;
        }
        BedwarsProcess bws = baritone.getBedwarsProcess();
        if (bws != null && bws.getMode() == BedwarsProcess.TaskMode.RECALL) {
            clutchChainTicks = 0;
            clutchStartY = Integer.MIN_VALUE;
            return false;
        }
        if (ctx.player().onGround()) {
            clutchChainTicks = 0;
            clutchStartY = Integer.MIN_VALUE;
            return false;
        }
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        int px = ctx.playerFeet().x;
        int py = ctx.playerFeet().y;
        int pz = ctx.playerFeet().z;
        boolean floorWithinTwo = false;
        double voidClearance = 22.0;
        for (int dy = 1; dy <= 20; dy++) {
            if (MovementHelper.canWalkOn(bsi, px, py - dy, pz)) {
                voidClearance = dy - 1.0;
                if (dy <= 2) {
                    floorWithinTwo = true;
                }
                break;
            }
        }
        Vec3 pos = ctx.player().position();
        Vec3 vel = ctx.player().getDeltaMovement();
        boolean triggered = clutchChainTicks > 0 || BedwarsTacticalHelper.shouldTriggerVoidClutch(
                ctx.player().onGround(),
                ctx.player().isFallFlying(),
                ctx.player().isInWater(),
                (float) ctx.player().fallDistance,
                vel.y,
                voidClearance,
                floorWithinTwo,
                false
        );
        if (!triggered) {
            return false;
        }
        if (clutchStartY == Integer.MIN_VALUE) {
            clutchStartY = py;
        }
        if (clutchStartY - py > 18) {
            clutchChainTicks = 0;
            return false;
        }
        if (!baritone.getInventoryBehavior().autoSwapHotbarBridgeBlock(0)) {
            return false;
        }
        List<ClutchCandidate> candidates = BedwarsTacticalHelper.rankClutchPlacements(
                pos.x,
                pos.y,
                pos.z,
                vel.x,
                vel.y,
                vel.z,
                (x, y, z) -> MovementHelper.canWalkOn(bsi, x, y, z) && !bsi.get0(x, y, z).canBeReplaced(),
                4.4
        );
        if (candidates.isEmpty()) {
            return false;
        }
        ClutchCandidate best = candidates.get(0);
        Vec3 eyePos = ctx.playerHead();
        Vec3 hitVec = new Vec3(
                best.againstX() + 0.5 + best.faceDx() * 0.5,
                best.againstY() + 0.5 + best.faceDy() * 0.5,
                best.againstZ() + 0.5 + best.faceDz() * 0.5
        );
        Rotation clutchRot = RotationUtils.calcRotationFromVec3d(eyePos, hitVec, ctx.playerRotations());
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(clutchRot, true, true);
        baritone.getInputOverrideHandler().clearAllKeys();
        float angErr = (float) Math.hypot(
                Rotation.normalizeYaw(clutchRot.getYaw() - ctx.playerRotations().getYaw()),
                clutchRot.getPitch() - ctx.playerRotations().getPitch()
        );
        if (angErr <= 15.0f) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            clutchChainTicks = clutchChainTicks > 0 ? Math.max(0, clutchChainTicks - 1) : 15;
            recordBlackboxPvpEvent("AUTOCLUTCH", "cell=" + best.placeX() + "," + best.placeY() + "," + best.placeZ());
        } else if (clutchChainTicks > 0) {
            clutchChainTicks--;
        }
        return true;
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
        for (Entity e : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (!e.isAlive() || e.isRemoved()) {
                continue;
            }
            String typePath = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            if (!"fireball".equals(typePath) && !"large_fireball".equals(typePath) && !"small_fireball".equals(typePath)) {
                continue;
            }
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
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(punchRot, false, true);
        baritone.getInputOverrideHandler().clearAllKeys();
        float angErr = (float) Math.hypot(
                Rotation.normalizeYaw(punchRot.getYaw() - ctx.playerRotations().getYaw()),
                punchRot.getPitch() - ctx.playerRotations().getPitch()
        );
        Minecraft mc = ctx.minecraft();
        boolean entityOnCrosshair = mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() == bestEntity;
        if (bestThreat.dist() <= 4.35 && (entityOnCrosshair || angErr <= 14.0f)) {
            if (Baritone.settings().bedwarsMode.value) {
                ((IMinecraft) mc).pathadol$setMissTime(0);
            }
            KeyMapping.click(resolveAttackKey(mc));
            lastFireballPunchTick = tickCounter;
            recordBlackboxPvpEvent("ANTI_FIREBALL",
                    "dist=" + String.format("%.2f", bestThreat.dist()) + ",mode=" + bestThreat.aimMode());
        }
        return true;
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
                    isRangedThreatType(living)
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
            double score = t.boxDist() + t.absFlankDeg() * 0.015;
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
                double ideal = primary.ranged() ? 2.15 : (wantAttackSpacing ? 2.78 : 2.45);
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
        double[] radii = {0.48, 0.92, 1.38, 1.95, 2.55};

        for (int rIdx = 0; rIdx < 16; rIdx++) {
            double yawRad = Math.toRadians(baseYaw + rIdx * 22.5f);
            double dirX = -Math.sin(yawRad);
            double dirZ = Math.cos(yawRad);
            double wDist = 2.65;
            double vDist = 2.65;

            for (double rad : radii) {
                double leadScale = rad <= 1.38 ? 1.4 : 0.6;
                double sampleX = pos.x + dirX * rad + vel.x * leadScale;
                double sampleZ = pos.z + dirZ * rad + vel.z * leadScale;
                int bx = (int) Math.floor(sampleX);
                int bz = (int) Math.floor(sampleZ);

                if (wDist > 2.60) {
                    if (!MovementHelper.canWalkThrough(bsi, bx, by, bz)
                            || !MovementHelper.canWalkThrough(bsi, bx, by + 1, bz)) {
                        wDist = rad;
                    }
                }
                if (vDist > 2.60) {
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
        return targetBox.inflate(0.05).clip(eyePos, end).isPresent();
    }
}
