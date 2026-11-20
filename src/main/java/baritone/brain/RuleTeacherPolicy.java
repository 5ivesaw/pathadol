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

package baritone.brain;

import baritone.bedwars.BedwarsMatchState;
import baritone.brain.BrainActionSpace.BrainDecision;
import baritone.brain.BrainSchema.ObsSnapshot;
import baritone.brain.BrainSchema.ThreatFeature;

/**
 * High-caliber scripted Bedwars &amp; PvP tactical teacher policy delegating directly to the
 * continuous IAUS utility engine in {@link DeterministicStrategyModel}.
 */
public final class RuleTeacherPolicy {

    private RuleTeacherPolicy() {}

    public static BrainDecision decide(ObsSnapshot s, boolean[] mask) {
        return decide(s, mask, null);
    }

    public static BrainDecision decide(ObsSnapshot s, boolean[] mask, BedwarsMatchState matchState) {
        return DeterministicStrategyModel.decide(s, mask, matchState);
    }

    /**
     * Detects when an enemy threatens us while bridging or near a void edge so the bot halts
     * backward bridging and fights/knocks them off before entering lethal knockback range.
     */
    public static boolean isBridgeConfrontation(ObsSnapshot s) {
        if (s == null || s.threats() == null || s.threats().isEmpty()) {
            return false;
        }
        ThreatFeature t0 = s.threats().get(0);
        if (!t0.present()) {
            return false;
        }
        boolean exposedOnBridge = s.voidSurroundFraction() >= 0.25 || s.frontVoidEdgeDist() < 4.5;
        if (!exposedOnBridge) {
            return false;
        }
        return t0.dist() <= 13.5
                || (t0.dist() <= 17.5 && (t0.radialClosingSpeed() >= 0.04 || t0.dangerScore() >= 0.36));
    }
}
