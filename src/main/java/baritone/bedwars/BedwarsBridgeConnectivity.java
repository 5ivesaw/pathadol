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

package baritone.bedwars;

/**
 * Fast, zero-allocation connected-surface &amp; parkour-jump reachability solver for BedWars maps.
 *
 * <p>Runs a single bounded Dijkstra expansion over walkable solid blocks (cardinal, diagonal, and
 * jumpable 1–3 block air gaps) from the player's current position so curved, angled, or L-shaped
 * bridges (e.g. Home &rarr; Diamond &rarr; Enemy) are recognized as connected ({@code blocksNeeded == 0})
 * with accurate curved walk distance instead of hallucinating a void gap via straight-line ray-marching.</p>
 *
 * <p>When an objective is not yet connected, selects the minimum-ETA reachable bridge-head frontier
 * cell and counts only the remaining void columns + climb blocks from that bridge-head to the objective.</p>
 */
public final class BedwarsBridgeConnectivity {

    public static final int NO_GROUND = Integer.MIN_VALUE;

    private static final int RADIUS = 96;
    private static final int SIZE = RADIUS * 2 + 1; // 193
    private static final int CELLS = SIZE * SIZE;   // 37,249
    private static final int MAX_EXPANDED = 4500;
    private static final int HEAP_CAP = 16384;

    private static final double SPRINT_BPS = 5.2;
    private static final double BRIDGE_BPS = 2.4;

    private static final int[] CARD_DX = {1, -1, 0, 0};
    private static final int[] CARD_DZ = {0, 0, 1, -1};
    private static final int[] DIAG_DX = {1, 1, -1, -1};
    private static final int[] DIAG_DZ = {1, -1, 1, -1};
    private static final int[] PROBE_DY = {0, 1, -1, -2, -3};

    @FunctionalInterface
    public interface SolidBlockProbe {
        boolean isSolid(int x, int y, int z);
    }

    @FunctionalInterface
    public interface ColumnGroundProbe {
        /**
         * Returns the walkable feet Y (solid at {@code y - 1}, free headroom at {@code y} and {@code y + 1})
         * near {@code refY}, or {@link #NO_GROUND} if the column is void or blocked.
         */
        int findWalkableY(int x, int refY, int z);
    }

    public record RouteEstimate(boolean connected, int blocksNeeded, double walkDistBlocks) {
        public double estimatedEtaSec() {
            return walkDistBlocks / SPRINT_BPS + blocksNeeded / BRIDGE_BPS;
        }
    }

    /**
     * Reusable primitive workspace avoiding per-tick GC allocations when evaluated at 5 Hz.
     */
    public static final class Workspace {
        final int[] stamp = new int[CELLS];
        final int[] dist = new int[CELLS];
        final short[] groundY = new short[CELLS];
        final byte[] expanded = new byte[CELLS];
        final int[] visitedCells = new int[MAX_EXPANDED];
        final int[] edgeCells = new int[MAX_EXPANDED];
        final int[] heapKey = new int[HEAP_CAP];
        final int[] heapIdx = new int[HEAP_CAP];

        int currentStamp = 0;
        int originX = 0;
        int originZ = 0;
        int startX = 0;
        int startY = 0;
        int startZ = 0;
        int visitedCount = 0;
        int edgeCount = 0;
        int heapSize = 0;
        ColumnGroundProbe lastProbe = null;

        void resetStamp(int sx, int sy, int sz, ColumnGroundProbe probe) {
            currentStamp++;
            if (currentStamp == Integer.MAX_VALUE) {
                java.util.Arrays.fill(stamp, 0);
                currentStamp = 1;
            }
            this.startX = sx;
            this.startY = sy;
            this.startZ = sz;
            this.originX = sx - RADIUS;
            this.originZ = sz - RADIUS;
            this.visitedCount = 0;
            this.edgeCount = 0;
            this.heapSize = 0;
            this.lastProbe = probe;
        }

        void heapPush(int key, int idx) {
            if (heapSize >= HEAP_CAP) {
                return;
            }
            int i = heapSize++;
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (heapKey[p] <= key) {
                    break;
                }
                heapKey[i] = heapKey[p];
                heapIdx[i] = heapIdx[p];
                i = p;
            }
            heapKey[i] = key;
            heapIdx[i] = idx;
        }

        int heapPopIdx() {
            int out = heapIdx[0];
            int lastKey = heapKey[--heapSize];
            int lastIdx = heapIdx[heapSize];
            int i = 0;
            int half = heapSize >>> 1;
            while (i < half) {
                int c = (i << 1) + 1;
                int right = c + 1;
                if (right < heapSize && heapKey[right] < heapKey[c]) {
                    c = right;
                }
                if (lastKey <= heapKey[c]) {
                    break;
                }
                heapKey[i] = heapKey[c];
                heapIdx[i] = heapIdx[c];
                i = c;
            }
            if (heapSize > 0) {
                heapKey[i] = lastKey;
                heapIdx[i] = lastIdx;
            }
            return out;
        }
    }

    private BedwarsBridgeConnectivity() {}

    /**
     * Standard walkable-column probe built on top of any solid-block predicate.
     */
    public static ColumnGroundProbe fromSolidProbe(SolidBlockProbe solid) {
        return (x, refY, z) -> {
            for (int dy : PROBE_DY) {
                int y = refY + dy;
                if (solid.isSolid(x, y - 1, z) && !solid.isSolid(x, y, z) && !solid.isSolid(x, y + 1, z)) {
                    return y;
                }
            }
            return NO_GROUND;
        };
    }

    /**
     * Populates {@code ws} with all walkable + jump-connected cells reachable from {@code (startX, startY, startZ)}.
     */
    public static void solve(Workspace ws, int startX, int startY, int startZ, ColumnGroundProbe probe) {
        ws.resetStamp(startX, startY, startZ, probe);
        if (probe == null) {
            return;
        }

        int seedY = probe.findWalkableY(startX, startY, startZ);
        if (seedY == NO_GROUND) {
            seedY = probe.findWalkableY(startX, startY + 1, startZ);
        }
        if (seedY != NO_GROUND) {
            relax(ws, startX, seedY, startZ, 0);
        } else {
            // Player mid-jump, on bottom slab, or perched slightly over edge: seed nearest solid ground in 3x3 around feet
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int sy = probe.findWalkableY(startX + dx, startY, startZ + dz);
                    if (sy == NO_GROUND) {
                        sy = probe.findWalkableY(startX + dx, startY + 1, startZ + dz);
                    }
                    if (sy != NO_GROUND) {
                        relax(ws, startX + dx, sy, startZ + dz, (dx != 0 && dz != 0) ? 14 : 10);
                    }
                }
            }
        }

        while (ws.heapSize > 0 && ws.visitedCount < MAX_EXPANDED) {
            int popKey = ws.heapKey[0];
            int idx = ws.heapPopIdx();
            if (ws.expanded[idx] != 0 || popKey > ws.dist[idx]) {
                continue;
            }
            ws.expanded[idx] = 1;
            ws.visitedCells[ws.visitedCount++] = idx;

            int lx = idx % SIZE;
            int lz = idx / SIZE;
            int cx = ws.originX + lx;
            int cz = ws.originZ + lz;
            int cy = ws.groundY[idx];
            int cd = ws.dist[idx];

            boolean hasVoidNeighbor = false;

            // 1. Cardinal steps + void-edge parkour jumps (1, 2, or 3-block air gaps)
            for (int dir = 0; dir < 4; dir++) {
                int dx = CARD_DX[dir];
                int dz = CARD_DZ[dir];
                int nx = cx + dx;
                int nz = cz + dz;
                int ny = probe.findWalkableY(nx, cy, nz);
                if (ny != NO_GROUND && ny <= cy + 1 && ny >= cy - 3) {
                    relax(ws, nx, ny, nz, cd + 10 + Math.abs(ny - cy) * 3);
                } else {
                    hasVoidNeighbor = true;
                    // Check jumpable 1-, 2-, and 3-block air gaps straight across the void edge
                    for (int step = 2; step <= 4; step++) {
                        int jx = cx + dx * step;
                        int jz = cz + dz * step;
                        int jy = probe.findWalkableY(jx, cy, jz);
                        if (jy != NO_GROUND) {
                            int maxJumpUp = (step <= 3) ? 1 : 0; // 3-block air gap (step=4) requires flat or downward landing
                            if (jy <= cy + maxJumpUp && jy >= cy - 3) {
                                int jumpCost = (step == 2) ? 22 : (step == 3 ? 32 : 44);
                                relax(ws, jx, jy, jz, cd + jumpCost + Math.abs(jy - cy) * 3);
                            }
                            break; // Stop scanning past the first solid landing along this ray
                        }
                    }
                }
            }

            // 2. Diagonal steps (handles 1-block-wide diagonal bridges seamlessly at 0 blocks needed)
            for (int dir = 0; dir < 4; dir++) {
                int nx = cx + DIAG_DX[dir];
                int nz = cz + DIAG_DZ[dir];
                int ny = probe.findWalkableY(nx, cy, nz);
                if (ny != NO_GROUND && ny <= cy + 1 && ny >= cy - 3) {
                    relax(ws, nx, ny, nz, cd + 14 + Math.abs(ny - cy) * 3);
                }
            }

            if (hasVoidNeighbor) {
                ws.edgeCells[ws.edgeCount++] = idx;
            }
        }
    }

    /**
     * Queries the solved connectivity workspace for the shortest path or minimum-ETA bridge route to {@code (tx, ty, tz)}.
     */
    public static RouteEstimate estimateRouteTo(Workspace ws, int tx, int ty, int tz) {
        if (ws == null || ws.visitedCount == 0 || ws.lastProbe == null) {
            double horiz = ws != null ? Math.hypot(tx - ws.startX, tz - ws.startZ) : 0.0;
            int fallbackGap = Math.max(0, (int) Math.round(horiz - 14.0));
            return new RouteEstimate(fallbackGap == 0, fallbackGap, Math.max(0.0, horiz - fallbackGap));
        }

        // 1. Check if any reachable walkable cell is already adjacent to the objective (connected via bridge/island/jump)
        double bestConnectedWalk = Double.POSITIVE_INFINITY;
        for (int i = 0; i < ws.visitedCount; i++) {
            int idx = ws.visitedCells[i];
            int vx = ws.originX + (idx % SIZE);
            int vz = ws.originZ + (idx / SIZE);
            int vy = ws.groundY[idx];
            if (Math.abs(vx - tx) <= 3 && Math.abs(vz - tz) <= 3 && Math.abs(vy - ty) <= 4) {
                double totalWalk = ws.dist[idx] / 10.0 + Math.hypot(tx - vx, tz - vz);
                if (totalWalk < bestConnectedWalk) {
                    bestConnectedWalk = totalWalk;
                }
            }
        }
        if (Double.isFinite(bestConnectedWalk)) {
            return new RouteEstimate(true, 0, bestConnectedWalk);
        }

        // 2. Objective not yet connected: pick top-3 minimum-ETA reachable bridge-head frontier cells
        int[] candidates = ws.edgeCount > 0 ? ws.edgeCells : ws.visitedCells;
        int candCount = ws.edgeCount > 0 ? ws.edgeCount : ws.visitedCount;

        int top0 = -1, top1 = -1, top2 = -1;
        double s0 = Double.POSITIVE_INFINITY, s1 = Double.POSITIVE_INFINITY, s2 = Double.POSITIVE_INFINITY;

        for (int i = 0; i < candCount; i++) {
            int idx = candidates[i];
            int vx = ws.originX + (idx % SIZE);
            int vz = ws.originZ + (idx / SIZE);
            int vy = ws.groundY[idx];
            double walk = ws.dist[idx] / 10.0;
            double remHoriz = Math.hypot(tx - vx, tz - vz);
            double remClimb = Math.max(0.0, ty - vy);
            double score = walk / SPRINT_BPS + (remHoriz + remClimb) / BRIDGE_BPS;
            if (score < s0) {
                s2 = s1; top2 = top1;
                s1 = s0; top1 = top0;
                s0 = score; top0 = idx;
            } else if (score < s1) {
                s2 = s1; top2 = top1;
                s1 = score; top1 = idx;
            } else if (score < s2) {
                s2 = score; top2 = idx;
            }
        }

        // 3. Ray-march actual void columns from each of the top-3 bridge-heads to (tx, ty, tz)
        RouteEstimate best = null;
        double bestEta = Double.POSITIVE_INFINITY;
        int[] topArr = {top0, top1, top2};
        for (int idx : topArr) {
            if (idx < 0) continue;
            int vx = ws.originX + (idx % SIZE);
            int vz = ws.originZ + (idx / SIZE);
            int vy = ws.groundY[idx];
            RouteEstimate est = rayMarchFromBridgeHead(ws.lastProbe, vx, vy, vz, ws.dist[idx] / 10.0, tx, ty, tz);
            double eta = est.estimatedEtaSec();
            if (eta < bestEta) {
                bestEta = eta;
                best = est;
            }
        }
        return best != null ? best : new RouteEstimate(false, 0, Math.hypot(tx - ws.startX, tz - ws.startZ));
    }

    private static RouteEstimate rayMarchFromBridgeHead(
            ColumnGroundProbe probe,
            int vx, int vy, int vz,
            double walkToHead,
            int tx, int ty, int tz
    ) {
        double horiz = Math.hypot(tx - vx, tz - vz);
        int steps = Math.max(1, (int) Math.ceil(horiz));
        int voidCols = 0;
        int solidAfterHead = 0;
        int prevX = vx;
        int prevZ = vz;
        int refY = vy;
        for (int s = 1; s <= steps; s++) {
            double t = s / (double) steps;
            int sx = (int) Math.round(vx + (tx - vx) * t);
            int sz = (int) Math.round(vz + (tz - vz) * t);
            if (sx == prevX && sz == prevZ) {
                continue;
            }
            prevX = sx;
            prevZ = sz;
            int interpY = (int) Math.round(vy + (ty - vy) * t);
            int gy = probe.findWalkableY(sx, interpY, sz);
            if (gy == NO_GROUND && interpY != refY) {
                gy = probe.findWalkableY(sx, refY, sz);
            }
            if (gy != NO_GROUND) {
                solidAfterHead++;
                refY = gy;
            } else {
                voidCols++;
            }
        }
        int climb = Math.max(0, ty - vy);
        int blocksReq = Math.max(1, voidCols + climb);
        return new RouteEstimate(false, blocksReq, walkToHead + solidAfterHead);
    }

    private static void relax(Workspace ws, int x, int y, int z, int newDist) {
        int lx = x - ws.originX;
        int lz = z - ws.originZ;
        if (lx < 0 || lx >= SIZE || lz < 0 || lz >= SIZE) {
            return;
        }
        int idx = lx + lz * SIZE;
        if (ws.stamp[idx] != ws.currentStamp) {
            ws.stamp[idx] = ws.currentStamp;
            ws.dist[idx] = newDist;
            ws.groundY[idx] = (short) y;
            ws.expanded[idx] = 0;
            ws.heapPush(newDist, idx);
        } else if (ws.expanded[idx] == 0 && newDist < ws.dist[idx]) {
            ws.dist[idx] = newDist;
            ws.groundY[idx] = (short) y;
            ws.heapPush(newDist, idx);
        }
    }
}
