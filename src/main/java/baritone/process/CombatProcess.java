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
import baritone.api.utils.input.Input;
import baritone.behavior.look.ForkableRandom;
import baritone.human.HumanProfile;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.AABB;
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

    private static final double ENGAGE_DIRECT_RANGE = 4.35;
    private static final double ATTACK_REACH = 2.98;
    private static final double HITBOX_SHRINK_RATIO = 0.14;

    private final ForkableRandom rand;
    private final Map<Integer, Long> targetReadyTick = new HashMap<>();

    private Predicate<Entity> targetFilter;
    private long tickCounter = 0L;
    private long lastCombatTick = -100L;
    private int currentPrimaryId = -1;
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
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(desiredRot, false, true);

        if (bodyDist > ENGAGE_DIRECT_RANGE) {
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(new GoalNear(target.blockPosition(), 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        baritone.getInputOverrideHandler().clearAllKeys();
        if (tickCounter < readyAt) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        lastCombatTick = tickCounter;

        CombatRuleset ruleset = CombatRuleset.fromSetting(Baritone.settings().humanCombatMode.value);
        float cooldown = ctx.player().getAttackStrengthScale(0.0f);
        boolean creeperSwelling = primaryThreat.creeperSwelling();
        boolean[] dirSafe8 = probeEightDirections(desiredRot.getYaw());
        boolean openFooting = updateMultiThreatSpacing(threats, primaryIdx, desiredRot.getYaw(), boxDist, ruleset, cooldown, dirSafe8);

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

    private boolean updateMultiThreatSpacing(
            List<ThreatSnapshot> threats,
            int primaryIdx,
            float desiredYaw,
            double boxDist,
            CombatRuleset ruleset,
            float cooldown,
            boolean[] dirSafe8
    ) {
        if (--strafeSwitchTicks <= 0) {
            strafeDir = -strafeDir;
            strafeSwitchTicks = 8 + (int) (rand.nextDouble() * 8.0);
        }

        ThreatSnapshot primary = threats.get(primaryIdx);
        Vec3 pos = ctx.player().position();
        boolean openFooting = dirSafe8[0] && dirSafe8[2] && dirSafe8[4] && dirSafe8[6];
        boolean safeBackward = dirSafe8[4]
                && !hasThreatInCone(threats, primaryIdx, pos.x, pos.z, desiredYaw + 180.0f, 3.4);

        if (primary.creeperSwelling() && boxDist < 4.5) {
            float kiteYaw = chooseBestKiteYaw(threats, primaryIdx, pos.x, pos.z, desiredYaw, true, true, strafeDir, dirSafe8);
            if (!Float.isNaN(kiteYaw)) {
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

        float kiteYaw = chooseBestKiteYaw(
                threats,
                primaryIdx,
                pos.x,
                pos.z,
                desiredYaw,
                wantAttackSpacing,
                retreating,
                strafeDir,
                dirSafe8
        );
        if (!Float.isNaN(kiteYaw)) {
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

    private boolean[] probeEightDirections(float baseYaw) {
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        boolean[] safe = new boolean[8];
        for (int i = 0; i < 8; i++) {
            safe[i] = isDirectionSafe(bsi, baseYaw + i * 45.0f);
        }
        return safe;
    }

    /**
     * Checks immediate step footing along the candidate movement yaw plus current horizontal velocity
     * across radii 0.45, 0.92, and 1.35 so combat kiting/strafing avoids void drops and liquids.
     */
    private boolean isDirectionSafe(BlockStateInterface bsi, double worldYawDeg) {
        double yawRad = Math.toRadians(worldYawDeg);
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);
        Vec3 pos = ctx.player().position();
        Vec3 vel = ctx.player().getDeltaMovement();
        int by = ctx.playerFeet().y;

        double[] radii = new double[]{0.45, 0.92, 1.35};
        for (double r : radii) {
            double sampleX = pos.x + dirX * r + vel.x * 1.8;
            double sampleZ = pos.z + dirZ * r + vel.z * 1.8;
            int bx = (int) Math.floor(sampleX);
            int bz = (int) Math.floor(sampleZ);

            if (bsi.get0(bx, by, bz).getBlock() instanceof LiquidBlock) {
                return false;
            }
            boolean hasFloor = false;
            for (int dy = 1; dy <= 2; dy++) {
                if (MovementHelper.canWalkOn(bsi, bx, by - dy, bz)) {
                    hasFloor = true;
                    break;
                }
            }
            if (!hasFloor) {
                return false;
            }
        }
        return true;
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
