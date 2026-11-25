package baritone.bedwars;

import net.minecraft.core.BlockPos;

/** Measures approach progress and time spent waiting for a pickup against game ticks. */
final class GeneratorProgress {
    private static final int APPROACH_LIMIT_TICKS = 35;

    private BlockPos target;
    private double bestDistance;
    private long lastProgressTick;
    private int resources;
    private int waitBudget;
    private boolean atPickup;

    boolean isStalled(BlockPos nextTarget, long tick, double distance, boolean waiting,
                      int currentResources, int waitBudgetTicks) {
        if (!nextTarget.equals(target) || waiting != atPickup || currentResources > resources
                || (!waiting && distance + 0.3 <= bestDistance)) {
            target = nextTarget;
            bestDistance = distance;
            lastProgressTick = tick;
            waitBudget = waitBudgetTicks;
            atPickup = waiting;
        }
        resources = currentResources;
        return tick - lastProgressTick >= (waiting ? waitBudget : APPROACH_LIMIT_TICKS);
    }

    static int waitBudgetTicks(boolean homeForge, double spawnSeconds) {
        if (homeForge) {
            return 140;
        }
        return spawnSeconds >= 0.0 ? (int) Math.ceil(Math.min(65.0, spawnSeconds + 3.0) * 20.0) : 280;
    }

    void reset() {
        target = null;
    }
}
