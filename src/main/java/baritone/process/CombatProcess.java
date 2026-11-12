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
import baritone.human.MouseModel;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.BlockStateInterface;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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

    private static final double ENGAGE_DIRECT_RANGE = 4.35;
    private static final double ATTACK_REACH = 3.05;
    private static final double HITBOX_SHRINK_RATIO = 0.15;

    private final ForkableRandom rand;
    private final Map<Integer, Long> targetReadyTick = new HashMap<>();

    private Predicate<Entity> targetFilter;
    private long tickCounter = 0L;
    private int strafeDir = 1;
    private int strafeSwitchTicks = 20;
    private int wTapTicks = 0;
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
        this.wTapTicks = 0;
        this.clickAccumulator = 0.0;
    }

    @Override
    public void cancel() {
        this.targetFilter = null;
        this.targetReadyTick.clear();
        this.wTapTicks = 0;
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

        Optional<LivingEntity> bestOpt = findBestTarget();
        if (bestOpt.isEmpty()) {
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        LivingEntity target = bestOpt.get();
        HumanProfile profile = HumanProfile.fromSettings();

        // Perception gate for newly acquired targets
        Long readyAt = targetReadyTick.get(target.getId());
        if (readyAt == null) {
            readyAt = tickCounter + (long) profile.sampleReactionTicks(rand, tickCounter);
            targetReadyTick.put(target.getId(), readyAt);
        }

        Vec3 eyePos = ctx.playerHead();
        // Predict target linear motion slightly (~1.2 ticks lead) inside shrunk hitbox so moving mobs don't trail behind crosshair
        Vec3 targetVel = target.getDeltaMovement();
        AABB ledBox = target.getBoundingBox().move(targetVel.x * 1.2, 0.0, targetVel.z * 1.2);
        Vec3 aimPoint = computeShrunkTargetPoint(eyePos, ledBox, HITBOX_SHRINK_RATIO);
        double distToPoint = eyePos.distanceTo(aimPoint);
        double bodyDist = ctx.player().distanceTo(target);

        Rotation desiredRot = RotationUtils.calcRotationFromVec3d(eyePos, aimPoint, ctx.playerRotations());
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(desiredRot, false, true);

        if (bodyDist > ENGAGE_DIRECT_RANGE) {
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(new GoalNear(target.blockPosition(), 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        // Direct close-range engagement
        baritone.getInputOverrideHandler().clearAllKeys();
        if (tickCounter < readyAt) {
            // Still reacting to newly spotted target
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        // Update strafe timer and void/ledge safety
        if (--strafeSwitchTicks <= 0) {
            strafeDir = -strafeDir;
            strafeSwitchTicks = 14 + (int) (rand.nextDouble() * 15.0);
        }

        boolean canStrafe = isStrafeSafe(strafeDir);
        if (!canStrafe && isStrafeSafe(-strafeDir)) {
            strafeDir = -strafeDir;
            canStrafe = true;
        }

        baritone.getInputOverrideHandler().setDesiredMovementYaw(desiredRot.getYaw());

        boolean safeForward = isDirectionSafe(desiredRot.getYaw());
        if (wTapTicks > 0) {
            wTapTicks--;
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            if (bodyDist < 2.0 && isDirectionSafe(desiredRot.getYaw() + 180.0f)) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            }
        } else if (bodyDist > 2.45 && safeForward) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, bodyDist > 2.8);
        } else if (bodyDist < 1.75 && isDirectionSafe(desiredRot.getYaw() + 180.0f)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
        }

        if (canStrafe) {
            baritone.getInputOverrideHandler().setInputForceState(
                    strafeDir > 0 ? Input.MOVE_RIGHT : Input.MOVE_LEFT,
                    true
            );
        }

        CombatRuleset ruleset = CombatRuleset.fromSetting(Baritone.settings().humanCombatMode.value);
        boolean crosshairAligned = isCrosshairOnTarget(eyePos, target.getBoundingBox(), ctx.playerRotations(), ATTACK_REACH + 0.55);

        if (ruleset == CombatRuleset.CLASSIC_1_8) {
            stepClassic18Attack(target, distToPoint, crosshairAligned, profile);
        } else {
            stepModernAttack(target, distToPoint, crosshairAligned, profile);
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private void stepClassic18Attack(LivingEntity target, double dist, boolean crosshairAligned, HumanProfile profile) {
        if (dist > ATTACK_REACH + 0.45) {
            clickAccumulator = Math.min(clickAccumulator, 0.4);
            return;
        }
        double jitterCps = profile.cpsMean() * (0.85 + rand.nextDouble() * 0.30);
        clickAccumulator += jitterCps / 20.0;
        if (clickAccumulator < 1.0) {
            return;
        }
        clickAccumulator -= 1.0;

        if (crosshairAligned && dist <= ATTACK_REACH) {
            performAttack(target);
        } else if (dist <= ATTACK_REACH + 0.35 && crosshairAligned) {
            // Natural pre-swing while closing in on 1.8
            ctx.player().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        }
    }

    private void stepModernAttack(LivingEntity target, double dist, boolean crosshairAligned, HumanProfile profile) {
        if (dist > ATTACK_REACH + 0.25) {
            return;
        }
        float cooldown = ctx.player().getAttackStrengthScale(0.0f);

        // Initiate jump crit when cooldown is nearly ready on flat ground
        if (ctx.player().onGround() && cooldown >= 0.72f && dist <= 2.95 && target.hurtTime <= 4) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        }

        if (cooldown >= 0.96f && crosshairAligned && dist <= ATTACK_REACH) {
            boolean readyAfterJitter = true;
            if (modernCooldownWaitJitter == 0 && rand.nextDouble() < 0.22) {
                modernCooldownWaitJitter = 1 + (int) (rand.nextDouble() * 2.0);
                readyAfterJitter = false;
            } else if (modernCooldownWaitJitter > 0) {
                modernCooldownWaitJitter--;
                readyAfterJitter = (modernCooldownWaitJitter == 0);
            }
            if (readyAfterJitter) {
                performAttack(target);
            }
        }
    }

    private void performAttack(LivingEntity target) {
        if (ctx.player() != null) {
            attackStrengthSum += ctx.player().getAttackStrengthScale(0.0f);
            swingsRecorded++;
        }
        if (ctx.minecraft().gameMode != null) {
            ctx.minecraft().gameMode.attack(ctx.player(), target);
        }
        ctx.player().swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        if (baritone.getInventoryBehavior().isHotbarReadyForAction()) {
            wTapTicks = 2;
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

    private Optional<LivingEntity> findBestTarget() {
        Vec3 eyePos = ctx.playerHead();
        Rotation currentRot = ctx.playerRotations();
        double maxRange = 28.0;

        return ctx.entitiesStream()
                .filter(e -> e instanceof LivingEntity)
                .map(e -> (LivingEntity) e)
                .filter(e -> !e.equals(ctx.player()) && e.isAlive() && !e.isRemoved())
                .filter(e -> targetFilter.test(e))
                .filter(e -> e.distanceTo(ctx.player()) <= maxRange)
                .filter(e -> ctx.player().hasLineOfSight(e) || e.distanceTo(ctx.player()) <= 4.5)
                .min(Comparator.comparingDouble(e -> scoreTarget(eyePos, currentRot, e)));
    }

    private double scoreTarget(Vec3 eyePos, Rotation currentRot, LivingEntity candidate) {
        double dist = candidate.distanceTo(ctx.player());
        Rotation toTarget = RotationUtils.calcRotationFromVec3d(eyePos, candidate.getBoundingBox().getCenter(), currentRot);
        float angleErr = MouseModel.angularDistance(currentRot, toTarget);
        return dist + angleErr * 0.04;
    }

    private boolean isStrafeSafe(int dir) {
        return isDirectionSafe(ctx.playerRotations().getYaw() + dir * 90.0f);
    }

    /**
     * Checks a 2-block corridor along the candidate movement yaw PLUS current horizontal velocity
     * so combat strafing or W-tapping never carries the player off a bridge or cliff into the void.
     */
    private boolean isDirectionSafe(double worldYawDeg) {
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        double yawRad = Math.toRadians(worldYawDeg);
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);
        Vec3 pos = ctx.player().position();
        Vec3 vel = ctx.player().getDeltaMovement();
        int by = ctx.playerFeet().y;

        double[] radii = new double[]{0.8, 1.4, 2.0};
        for (double r : radii) {
            double sampleX = pos.x + dirX * r + vel.x * 2.5;
            double sampleZ = pos.z + dirZ * r + vel.z * 2.5;
            int bx = (int) Math.floor(sampleX);
            int bz = (int) Math.floor(sampleZ);

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
