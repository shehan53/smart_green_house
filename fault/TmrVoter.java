package com.greenhouse.fault;

import com.greenhouse.monitor.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Author: Role 2 - Fault Tolerance Engineer
 * Concepts: #3 Triple Modular Redundancy with INEXACT majority voting and graceful degradation
 * Purpose: Combines the readings of T1, T2, T3. Two readings "agree" if they differ by at most
 *          +/-1.0 C (inexact voting - real sensors never give identical numbers).
 *            3 agree               -> OK       (median)
 *            2 agree, 1 outlier    -> MASKED   (outlier out-voted, fault masked)
 *            only 2 left, agree    -> DEGRADED (graceful degradation 3 -> 2)
 *            otherwise             -> FAILED   (controller goes to the SAFE STATE)
 */
public class TmrVoter {

    public static final double TOLERANCE = 1.0;

    /** @param inputs trustworthy temperature readings (sensor id -> value), 0..3 entries */
    public VoteResult vote(Map<String, Double> inputs) {
        List<String> ids = new ArrayList<>(inputs.keySet());
        List<Double> v = new ArrayList<>(inputs.values());
        int n = ids.size();

        if (n == 3) {
            boolean ab = agree(v.get(0), v.get(1));
            boolean ac = agree(v.get(0), v.get(2));
            boolean bc = agree(v.get(1), v.get(2));
            if (ab && ac && bc) {
                return new VoteResult(VoteStatus.OK, median(v.get(0), v.get(1), v.get(2)), 3,
                        "all 3 agree " + list(ids, v));
            }
            // CONCEPT 3: majority 2-of-3 -> choose the closest agreeing pair, out-vote the third
            int[][] pairs = {{0, 1, 2}, {0, 2, 1}, {1, 2, 0}};
            int[] best = null;
            double bestDiff = Double.MAX_VALUE;
            for (int[] p : pairs) {
                double diff = Math.abs(v.get(p[0]) - v.get(p[1]));
                if (diff <= TOLERANCE && diff < bestDiff) {
                    bestDiff = diff;
                    best = p;
                }
            }
            if (best != null) {
                double voted = (v.get(best[0]) + v.get(best[1])) / 2.0;
                return new VoteResult(VoteStatus.MASKED, voted, 2,
                        ids.get(best[2]) + "=" + Log.f1(v.get(best[2])) + " C out-voted (fault masked), "
                                + ids.get(best[0]) + " and " + ids.get(best[1]) + " agree");
            }
            return new VoteResult(VoteStatus.FAILED, Double.NaN, 0,
                    "no two sensors agree within +/-1.0 C " + list(ids, v));
        }

        if (n == 2) {
            if (agree(v.get(0), v.get(1))) {
                // CONCEPT 3: graceful degradation - keep working with 2 sensors
                return new VoteResult(VoteStatus.DEGRADED, (v.get(0) + v.get(1)) / 2.0, 2,
                        "only 2 sensors " + list(ids, v) + " - they agree");
            }
            return new VoteResult(VoteStatus.FAILED, Double.NaN, 0,
                    "only 2 sensors and they DISAGREE " + list(ids, v) + " - cannot tell which is right");
        }

        return new VoteResult(VoteStatus.FAILED, Double.NaN, 0,
                "only " + n + " trustworthy temperature sensor(s) - no majority possible");
    }

    private static boolean agree(double a, double b) {
        return Math.abs(a - b) <= TOLERANCE;
    }

    private static double median(double a, double b, double c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    private static String list(List<String> ids, List<Double> values) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(ids.get(i)).append('=').append(Log.f1(values.get(i)));
        }
        return sb.append(')').toString();
    }
}
