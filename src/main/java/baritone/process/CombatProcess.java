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
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.ICombatProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.VecUtils;
import baritone.api.utils.input.Input;
import baritone.behavior.look.ForkableRandom;
import baritone.human.HumanProfile;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            boolean creeperSwelling
    ) {}

    /**
     * 16-ray spatial clearance environment around the player: {@code safe}, nearest solid
     * obstacle distance {@code wallDist}, and nearest void/liquid edge distance {@code voidDist}.
     */
    public record RayEnv16(boolean[] safe, double[] wallDist, double[] voidDist) {}

    private static final double ENGAGE_DIRECT_RANGE = 4.35;
    private static final double ENGAGE_CHASE_MAX_RANGE = 5.20;
    private static final double ATTACK_REACH = 2.98;
    private static final double HITBOX_SHRINK_RATIO = 0.14;

    private final ForkableRandom rand;
    private final Map<Integer, Long> targetReadyTick = new HashMap<>();

    private Predicate<Entity> targetFilter;
    private long tickCounter = 0L;
    private long lastCombatTick = -100L;
    private int currentPrimaryId = -1;
    private Float prevKiteYaw = null;
    private int strafeDir = 1;
    private int strafeSwitchTicks = 10;
    private int wTapTicks = 0;
    private int sTapTicks = 0;
    private int critHoldTicks = 0;
    private double clickAccumulator = 0.0;
    private int modernCooldownWaitJitter = 0;
    private int swingsRecorded = 0;
    private double attackStrengthSum = 0.0;

    public CombatProcess(Baritone baritone) {
        super(baritone);
        long seed = Baritone.settings().humanSeed.value;
        this.rand = seed != 0L ? new ForkableRandom(seed ^ 0xA5A5A5A55A5A5A5AL) : new ForkableRandom();
    }

    @Override
    public void attack(Predicate<Entity> filter) {
        this.targetFilter = filter;
        this.targetReadyTick.clear();
        this.currentPrimaryId = -1;
        this.prevKiteYaw = null;
        this.lastCombatTick = -100L;
        this.wTapTicks = 0;
        this.sTapTicks = 0;
        this.critHoldTicks = 0;
        this.clickAccumulator = 0.0;
    }

    @Override
    public void cancel() {
        this.targetFilter = null;
        this.targetReadyTick.clear();
        this.currentPrimaryId = -1;
        this.prevKiteYaw = null;
        this.lastCombatTick = -100L;
        this.wTapTicks = 0;
        this.sTapTicks = 0;
        this.critHoldTicks = 0;
        this.clickAccumulator = 0.0;
        baritone.getInputOverrideHandler().clearAllKeys();
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
        if (ctx.player() == null || ctx.world() == null) {
            return null;
        }

        List<LivingEntity> candidates = new ArrayList<>();
        List<ThreatSnapshot> threats = new ArrayList<>();
        collectTargets(candidates, threats);
        if (threats.isEmpty()) {
            this.currentPrimaryId = -1;
            this.prevKiteYaw = null;
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        int primaryIdx = selectPrimaryThreatIndex(threats, currentPrimaryId);
        LivingEntity target = candidates.get(primaryIdx);
        ThreatSnapshot primaryThreat = threats.get(primaryIdx);
        this.currentPrimaryId = target.getId();
        HumanProfile profile = HumanProfile.fromSettings();

        // Apply initial perception gate; mid-brawl threat switches react cleanly without freezing
        Long readyAt = targetReadyTick.get(target.getId());
        if (readyAt == null) {
            long delay = (tickCounter - lastCombatTick <= 6L) ? 0L : (long) profile.sampleReactionTicks(rand, tickCounter);
            readyAt = tickCounter + delay;
            targetReadyTick.put(target.getId(), readyAt);
        }

        Vec3 eyePos = ctx.playerHead();
        // Predict target linear motion slightly (~1.2 ticks lead) inside shrunk hitbox so moving mobs don't trail behind crosshair
        Vec3 targetVel = target.getDeltaMovement();
        AABB ledBox = target.getBoundingBox().move(
                targetVel.x * 1.2,
                Math.max(-0.20, Math.min(0.20, targetVel.y * 0.8)),
                targetVel.z * 1.2
        );
        Vec3 aimPoint = computeShrunkTargetPoint(eyePos, ledBox, HITBOX_SHRINK_RATIO);
        double boxDist = primaryThreat.boxDist();
        double bodyDist = ctx.player().distanceTo(target);

        Rotation desiredRot = RotationUtils.calcRotationFromVec3d(eyePos, aimPoint, ctx.playerRotations());
        boolean hasLos = ctx.player().hasLineOfSight(target);

        if (!hasLos) {
            if (tryDirectBarrierBreach(eyePos, target, desiredRot.getYaw())) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            prevKiteYaw = null;
            return new PathingCommand(new GoalNear(target.blockPosition(), 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        RayEnv16 env16 = probeEnvironment16(desiredRot.getYaw());
        if (bodyDist > ENGAGE_DIRECT_RANGE) {
            if (bodyDist <= ENGAGE_CHASE_MAX_RANGE && env16.safe()[0] && env16.voidDist()[0] >= 1.95) {
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
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            prevKiteYaw = null;
            return new PathingCommand(new GoalNear(target.blockPosition(), 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(desiredRot, false, true);
        baritone.getInputOverrideHandler().clearAllKeys();
        if (tickCounter < readyAt) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        lastCombatTick = tickCounter;

        CombatRuleset ruleset = CombatRuleset.fromSetting(Baritone.settings().humanCombatMode.value);
        float cooldown = ctx.player().getAttackStrengthScale(0.0f);
        boolean creeperSwelling = primaryThreat.creeperSwelling();
        boolean openFooting = updateMultiThreatSpacing(threats, primaryIdx, desiredRot.getYaw(), boxDist, ruleset, cooldown, env16);

        boolean swarmed = hasCloseSecondaryThreat(threats, primaryIdx, 2.35);
        boolean hasSecondaryThreat = hasCloseSecondaryThreat(threats, primaryIdx, 3.60);
        boolean crosshairAligned = isCrosshairOnTarget(eyePos, target.getBoundingBox(), ctx.playerRotations(), 3.25);
        if (ruleset == CombatRuleset.CLASSIC_1_8) {
            stepClassic18Attack(target, boxDist, crosshairAligned, profile);
        } else {
            boolean allowJumpCrit = openFooting && !creeperSwelling && !hasSecondaryThreat;
            stepModernAttack(target, boxDist, crosshairAligned, allowJumpCrit, swarmed);
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean tryDirectBarrierBreach(Vec3 eyePos, LivingEntity target, float yawToTarget) {
        if (!Baritone.settings().allowBreak.value) {
            return false;
        }
        Vec3 aimEnd = target.getBoundingBox().getCenter();
        BlockHitResult hit = ctx.world().clip(new ClipContext(eyePos, aimEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player()));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos hitPos = hit.getBlockPos();
        Vec3 hitCenter = VecUtils.getBlockPosCenter(hitPos);
        double dist = eyePos.distanceTo(hitCenter);
        if (dist > 4.25) {
            return false;
        }
        BlockState state = ctx.world().getBlockState(hitPos);
        float speed = state.getDestroySpeed(ctx.world(), hitPos);
        if (speed < 0.0f || speed > 8.0f || Baritone.settings().blocksToDisallowBreaking.value.contains(state.getBlock())) {
            return false;
        }

        Rotation breakRot = RotationUtils.calcRotationFromVec3d(eyePos, hitCenter, ctx.playerRotations());
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(breakRot, false, true);
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setDesiredMovementYaw(yawToTarget);
        if (dist > 2.35) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        }
        if (baritone.getInventoryBehavior().isSlotSwitchSafe()) {
            MovementHelper.switchToBestToolFor(ctx, state);
        }
        if (baritone.getInventoryBehavior().isHotbarReadyForAction()) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        }
        return true;
    }

    private boolean updateMultiThreatSpacing(
            List<ThreatSnapshot> threats,
            int primaryIdx,
            float desiredYaw,
            double boxDist,
            CombatRuleset ruleset,
            float cooldown,
            RayEnv16 env
    ) {
        if (--strafeSwitchTicks <= 0) {
            strafeDir = -strafeDir;
            strafeSwitchTicks = 8 + (int) (rand.nextDouble() * 8.0);
        }

        ThreatSnapshot primary = threats.get(primaryIdx);
        Vec3 pos = ctx.player().position();
        boolean openFooting = env.safe()[0] && env.safe()[4] && env.safe()[8] && env.safe()[12];
        boolean safeBackward = env.safe()[8]
                && env.wallDist()[8] >= 1.95
                && env.voidDist()[8] >= 2.35
                && !hasThreatInCone(threats, primaryIdx, pos.x, pos.z, desiredYaw + 180.0f, 3.4);

        if (primary.creeperSwelling() && boxDist < 4.5) {
            float kiteYaw = chooseContextKiteYaw16(threats, primaryIdx, pos.x, pos.z, desiredYaw, prevKiteYaw, true, true, strafeDir, env);
            if (!Float.isNaN(kiteYaw)) {
                prevKiteYaw = kiteYaw;
                baritone.getInputOverrideHandler().setDesiredMovementYaw(kiteYaw);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            }
            return false;
        }

        if (sTapTicks > 0) {
            sTapTicks--;
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            if (safeBackward) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            }
            return openFooting;
        }
        if (wTapTicks > 0) {
            wTapTicks--;
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            if (boxDist < 2.25 && safeBackward) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            }
            return openFooting;
        }

        boolean urgentSecondary = hasUrgentSecondaryCharger(threats, primaryIdx, 4.2, 10.0);
        boolean wantAttackSpacing = (ruleset == CombatRuleset.MODERN_COOLDOWN && cooldown < 0.88f)
                || (threats.size() >= 2 && urgentSecondary && boxDist < 3.1 && cooldown < 0.90f);
        boolean retreating = urgentSecondary && boxDist < 2.65 && ruleset == CombatRuleset.MODERN_COOLDOWN && cooldown < 0.85f;

        float kiteYaw = chooseContextKiteYaw16(
                threats,
                primaryIdx,
                pos.x,
                pos.z,
                desiredYaw,
                prevKiteYaw,
                wantAttackSpacing,
                retreating,
                strafeDir,
                env
        );
        if (!Float.isNaN(kiteYaw)) {
            prevKiteYaw = kiteYaw;
            baritone.getInputOverrideHandler().setDesiredMovementYaw(kiteYaw);
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);

            float moveRelYaw = Math.abs(Rotation.normalizeYaw(kiteYaw - desiredYaw));
            boolean closingForward = moveRelYaw <= 46.0f && boxDist > 2.68;
            boolean critDescending = ruleset == CombatRuleset.MODERN_COOLDOWN && !ctx.player().onGround();
            boolean allowSprint = closingForward && !wantAttackSpacing && !retreating && !critDescending
                    && (ruleset == CombatRuleset.CLASSIC_1_8 || boxDist > 2.95);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, allowSprint);
        }
        return openFooting;
    }

    private void stepClassic18Attack(LivingEntity target, double boxDist, boolean crosshairAligned, HumanProfile profile) {
        if (boxDist > ATTACK_REACH + 0.45) {
            clickAccumulator = Math.min(clickAccumulator, 0.4);
            return;
        }
        double effectiveCps = Math.max(8.5, Math.min(16.5, profile.cpsMean()));
        double jitterCps = effectiveCps * (0.85 + rand.nextDouble() * 0.30);
        clickAccumulator += jitterCps / 20.0;
        if (clickAccumulator < 1.0) {
            return;
        }
        clickAccumulator -= 1.0;

        if (crosshairAligned && boxDist <= ATTACK_REACH) {
            performAttack(target, CombatRuleset.CLASSIC_1_8, boxDist);
        } else if (boxDist <= ATTACK_REACH + 0.35 && crosshairAligned) {
            // Natural pre-swing while closing in on 1.8
            ctx.player().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        }
    }

    private void stepModernAttack(
            LivingEntity target,
            double boxDist,
            boolean crosshairAligned,
            boolean allowJumpCrit,
            boolean swarmed
    ) {
        if (boxDist > ATTACK_REACH + 0.25) {
            critHoldTicks = 0;
            return;
        }
        float cooldown = ctx.player().getAttackStrengthScale(0.0f);
        float requiredCooldown = swarmed ? 0.94f : 0.96f;

        // Initiate jump crit only on wide open footing with no flanking secondary threats
        if (allowJumpCrit && ctx.player().onGround() && cooldown >= 0.72f && boxDist <= 2.90 && target.hurtTime <= 4) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            ctx.player().setSprinting(false);
        }

        // Hold up to 4 ticks mid-air for downward fall (vel.y < -0.04) & cancel sprint for a true 1.5x crit
        boolean airborne = !ctx.player().onGround();
        if (airborne && cooldown >= requiredCooldown) {
            ctx.player().setSprinting(false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            if (ctx.player().getDeltaMovement().y >= -0.04 && boxDist <= 2.85 && critHoldTicks < 4) {
                critHoldTicks++;
                return;
            }
        }
        critHoldTicks = 0;

        if (cooldown >= requiredCooldown && crosshairAligned && boxDist <= ATTACK_REACH) {
            boolean readyAfterJitter = true;
            if (!airborne) {
                if (modernCooldownWaitJitter == 0 && rand.nextDouble() < 0.14) {
                    modernCooldownWaitJitter = 1;
                    readyAfterJitter = false;
                } else if (modernCooldownWaitJitter > 0) {
                    modernCooldownWaitJitter--;
                    readyAfterJitter = (modernCooldownWaitJitter == 0);
                }
            }
            if (readyAfterJitter) {
                performAttack(target, CombatRuleset.MODERN_COOLDOWN, boxDist);
            }
        }
    }

    private void performAttack(LivingEntity target, CombatRuleset ruleset, double boxDist) {
        if (ctx.player() != null) {
            if (ruleset == CombatRuleset.MODERN_COOLDOWN && !ctx.player().onGround()) {
                ctx.player().setSprinting(false);
            }
            attackStrengthSum += ctx.player().getAttackStrengthScale(0.0f);
            swingsRecorded++;
        }
        if (ctx.minecraft().gameMode != null) {
            ctx.minecraft().gameMode.attack(ctx.player(), target);
        }
        ctx.player().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        if (ruleset == CombatRuleset.CLASSIC_1_8 && baritone.getInventoryBehavior().isHotbarReadyForAction()) {
            if (boxDist < 2.35) {
                sTapTicks = 2;
            } else {
                wTapTicks = 2;
            }
        }
    }

    public void resetTelemetry() {
        this.swingsRecorded = 0;
        this.attackStrengthSum = 0.0;
    }

    public int getSwingsRecorded() {
        return swingsRecorded;
    }

    public double getMeanAttackStrengthAtSwing() {
        return swingsRecorded == 0 ? 0.0 : (attackStrengthSum / swingsRecorded);
    }

    private void collectTargets(List<LivingEntity> candidates, List<ThreatSnapshot> threats) {
        Vec3 eyePos = ctx.playerHead();
        Vec3 playerPos = ctx.player().position();
        Rotation currentRot = ctx.playerRotations();
        double maxRange = 28.0;
        double blindRange = Baritone.settings().allowBreak.value ? 16.0 : 4.5;

        for (Entity entity : (Iterable<Entity>) ctx.entitiesStream()::iterator) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (living.equals(ctx.player()) || !living.isAlive() || living.isRemoved()) {
                continue;
            }
            if (!targetFilter.test(living)) {
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
                    creeperSwelling
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
            if (primary.creeperSwelling() || retreating) {
                score = (dPrimProj - dPrimCur) * 3.0 + Math.min(6.0, dPrimProj) * 0.45;
            } else {
                double ideal = wantAttackSpacing ? 3.25 : 2.55;
                score = -Math.abs(dPrimProj - ideal) * 2.3;
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

            double wDist = env.wallDist()[r];
            double vDist = env.voidDist()[r];
            if (wDist < 2.2) {
                score -= (2.2 - wDist) * 1.75;
            }
            score += Math.min(2.6, wDist) * 0.45;

            if (vDist < 2.4) {
                score -= (2.4 - vDist) * 2.15;
            }
            // Strongly discourage stepping onto a void edge aligned behind knockback from primary attacker
            double stepDotAway = stepX * awayX + stepZ * awayZ;
            if (stepDotAway > 0.20 && vDist < 2.45) {
                score -= (2.45 - vDist) * (2.6 + 2.2 * stepDotAway);
            }

            if (env.safe()[(r + 15) & 15]) {
                score += 0.22;
            }
            if (env.safe()[(r + 1) & 15]) {
                score += 0.22;
            }
            if (!retreating && !primary.creeperSwelling()) {
                score += 0.42 * (stepX * strafeX + stepZ * strafeZ);
            }
            if (prevKiteYaw != null) {
                score += 0.38 * Math.cos(Math.toRadians(Rotation.normalizeYaw(candYaw - prevKiteYaw)));
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
