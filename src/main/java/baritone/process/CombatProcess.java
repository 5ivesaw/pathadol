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
import net.minecraft.world.entity.monster.Creeper;
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
    private static final double ATTACK_REACH = 2.98;
    private static final double HITBOX_SHRINK_RATIO = 0.14;

    private final ForkableRandom rand;
    private final Map<Integer, Long> targetReadyTick = new HashMap<>();

    private Predicate<Entity> targetFilter;
    private long tickCounter = 0L;
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
        this.wTapTicks = 0;
        this.sTapTicks = 0;
        this.critHoldTicks = 0;
        this.clickAccumulator = 0.0;
    }

    @Override
    public void cancel() {
        this.targetFilter = null;
        this.targetReadyTick.clear();
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
        AABB ledBox = target.getBoundingBox().move(
                targetVel.x * 1.2,
                Math.max(-0.20, Math.min(0.20, targetVel.y * 0.8)),
                targetVel.z * 1.2
        );
        Vec3 aimPoint = computeShrunkTargetPoint(eyePos, ledBox, HITBOX_SHRINK_RATIO);
        double boxDist = distanceToAabb(eyePos, target.getBoundingBox());
        double bodyDist = ctx.player().distanceTo(target);

        Rotation desiredRot = RotationUtils.calcRotationFromVec3d(eyePos, aimPoint, ctx.playerRotations());
        ((baritone.behavior.LookBehavior) baritone.getLookBehavior()).updateTarget(desiredRot, false, true);

        if (bodyDist > ENGAGE_DIRECT_RANGE) {
            baritone.getInputOverrideHandler().clearAllKeys();
            return new PathingCommand(new GoalNear(target.blockPosition(), 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }

        // Direct close-range engagement: clear desiredMovementYaw so direct WASD kiting/strafing/W-tapping isn't overwritten by KeyboardModel
        baritone.getInputOverrideHandler().clearAllKeys();
        if (tickCounter < readyAt) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        CombatRuleset ruleset = CombatRuleset.fromSetting(Baritone.settings().humanCombatMode.value);
        float cooldown = ctx.player().getAttackStrengthScale(0.0f);
        boolean creeperSwelling = target instanceof Creeper creeper
                && (creeper.getSwellDir() > 0 || creeper.getSwelling(0.0f) > 0.20f);
        boolean openFooting = updateMovementAndSpacing(desiredRot, boxDist, bodyDist, ruleset, cooldown, creeperSwelling);

        boolean crosshairAligned = isCrosshairOnTarget(eyePos, target.getBoundingBox(), ctx.playerRotations(), 3.25);
        if (ruleset == CombatRuleset.CLASSIC_1_8) {
            stepClassic18Attack(target, boxDist, crosshairAligned, profile);
        } else {
            stepModernAttack(target, boxDist, crosshairAligned, openFooting && !creeperSwelling);
        }

        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private boolean updateMovementAndSpacing(
            Rotation desiredRot,
            double boxDist,
            double bodyDist,
            CombatRuleset ruleset,
            float cooldown,
            boolean creeperSwelling
    ) {
        if (--strafeSwitchTicks <= 0) {
            strafeDir = -strafeDir;
            strafeSwitchTicks = 8 + (int) (rand.nextDouble() * 8.0);
        }

        boolean safeLeft = isStrafeSafe(-1);
        boolean safeRight = isStrafeSafe(1);
        boolean canStrafe = strafeDir < 0 ? safeLeft : safeRight;
        if (!canStrafe) {
            int alt = -strafeDir;
            if (alt < 0 ? safeLeft : safeRight) {
                strafeDir = alt;
                canStrafe = true;
            }
        }

        boolean safeForward = isDirectionSafe(desiredRot.getYaw());
        boolean safeBackward = isDirectionSafe(desiredRot.getYaw() + 180.0f);

        if (creeperSwelling && bodyDist < 4.6) {
            if (safeBackward) {
                baritone.getInputOverrideHandler().setDesiredMovementYaw(desiredRot.getYaw() + 180.0f);
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
            } else if (safeForward && bodyDist > 3.2) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, false);
            }
            if (canStrafe) {
                baritone.getInputOverrideHandler().setInputForceState(
                        strafeDir > 0 ? Input.MOVE_RIGHT : Input.MOVE_LEFT,
                        true
                );
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
        } else if (wTapTicks > 0) {
            wTapTicks--;
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            if (boxDist < 2.25 && safeBackward) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            }
        } else if (ruleset == CombatRuleset.MODERN_COOLDOWN) {
            boolean waitingCooldown = cooldown < 0.80f;
            if (waitingCooldown) {
                if (boxDist < 2.65 && safeBackward) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                } else if (boxDist > 3.25 && cooldown >= 0.55f && safeForward) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                    baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, boxDist > 3.55);
                }
            } else {
                // Cooldown nearly ready: hold optimal edge-reach strike range (2.40 - 2.90m)
                if (boxDist > 2.75 && safeForward) {
                    boolean critDescending = !ctx.player().onGround();
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                    baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, boxDist > 3.05 && !critDescending);
                } else if (boxDist < 2.05 && safeBackward) {
                    baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
                }
            }
        } else {
            // Classic 1.8: hold edge combo spacing around 2.45 - 2.85m
            if (boxDist > 2.65 && safeForward) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
                baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
            } else if (boxDist < 2.05 && safeBackward) {
                baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_BACK, true);
            }
        }

        // Bias inward strafe when standing near a narrow bridge drop so circle-strafing keeps centered footing
        if (canStrafe && (safeLeft && safeRight || boxDist < 3.4)) {
            baritone.getInputOverrideHandler().setInputForceState(
                    strafeDir > 0 ? Input.MOVE_RIGHT : Input.MOVE_LEFT,
                    true
            );
        }
        return safeLeft && safeRight && safeForward && safeBackward;
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

    private void stepModernAttack(LivingEntity target, double boxDist, boolean crosshairAligned, boolean allowJumpCrit) {
        if (boxDist > ATTACK_REACH + 0.25) {
            critHoldTicks = 0;
            return;
        }
        float cooldown = ctx.player().getAttackStrengthScale(0.0f);

        // Initiate jump crit only on open footing (never risk knockback off narrow void bridges)
        if (allowJumpCrit && ctx.player().onGround() && cooldown >= 0.72f && boxDist <= 2.90 && target.hurtTime <= 4) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            ctx.player().setSprinting(false);
        }

        // Wait up to 3 ticks mid-air for falling trajectory & cancel sprint so the hit lands as a true 1.5x crit
        if (!ctx.player().onGround() && cooldown >= 0.96f) {
            ctx.player().setSprinting(false);
            baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, false);
            if (ctx.player().getDeltaMovement().y > -0.015 && boxDist <= 2.82 && critHoldTicks < 3) {
                critHoldTicks++;
                return;
            }
        }
        critHoldTicks = 0;

        if (cooldown >= 0.96f && crosshairAligned && boxDist <= ATTACK_REACH) {
            boolean readyAfterJitter = true;
            if (modernCooldownWaitJitter == 0 && rand.nextDouble() < 0.16) {
                modernCooldownWaitJitter = 1;
                readyAfterJitter = false;
            } else if (modernCooldownWaitJitter > 0) {
                modernCooldownWaitJitter--;
                readyAfterJitter = (modernCooldownWaitJitter == 0);
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

    private Optional<LivingEntity> findBestTarget() {
        Vec3 eyePos = ctx.playerHead();
        Rotation currentRot = ctx.playerRotations();
        double maxRange = 28.0;

        double blindRange = Baritone.settings().allowBreak.value ? 16.0 : 4.5;
        return ctx.entitiesStream()
                .filter(e -> e instanceof LivingEntity)
                .map(e -> (LivingEntity) e)
                .filter(e -> !e.equals(ctx.player()) && e.isAlive() && !e.isRemoved())
                .filter(e -> targetFilter.test(e))
                .filter(e -> e.distanceTo(ctx.player()) <= maxRange)
                .filter(e -> ctx.player().hasLineOfSight(e) || e.distanceTo(ctx.player()) <= blindRange)
                .min(Comparator.comparingDouble(e -> scoreTarget(eyePos, currentRot, e)));
    }

    private double scoreTarget(Vec3 eyePos, Rotation currentRot, LivingEntity candidate) {
        double dist = candidate.distanceTo(ctx.player());
        Rotation toTarget = RotationUtils.calcRotationFromVec3d(eyePos, candidate.getBoundingBox().getCenter(), currentRot);
        float angleErr = MouseModel.angularDistance(currentRot, toTarget);
        double score = dist + angleErr * 0.03;
        if (candidate instanceof Creeper creeper && creeper.getSwellDir() > 0 && dist < 5.0) {
            score -= 2.5;
        }
        if (candidate.getHealth() <= 6.0f && dist < 4.5) {
            score -= 0.6;
        }
        if (candidate.hurtTime > 5) {
            score += 0.9 * (candidate.hurtTime / 10.0);
        }
        return score;
    }

    private static double distanceToAabb(Vec3 point, AABB box) {
        double cx = Math.max(box.minX, Math.min(box.maxX, point.x));
        double cy = Math.max(box.minY, Math.min(box.maxY, point.y));
        double cz = Math.max(box.minZ, Math.min(box.maxZ, point.z));
        return point.distanceTo(new Vec3(cx, cy, cz));
    }

    private boolean isStrafeSafe(int dir) {
        return isDirectionSafe(ctx.playerRotations().getYaw() + dir * 90.0f);
    }

    /**
     * Checks immediate step footing along the candidate movement yaw plus current horizontal velocity
     * so combat strafing works cleanly on 2-wide bridges without walking into the void.
     */
    private boolean isDirectionSafe(double worldYawDeg) {
        BlockStateInterface bsi = new BlockStateInterface(ctx);
        double yawRad = Math.toRadians(worldYawDeg);
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);
        Vec3 pos = ctx.player().position();
        Vec3 vel = ctx.player().getDeltaMovement();
        int by = ctx.playerFeet().y;

        double[] radii = new double[]{0.45, 0.92};
        for (double r : radii) {
            double sampleX = pos.x + dirX * r + vel.x * 1.8;
            double sampleZ = pos.z + dirZ * r + vel.z * 1.8;
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
