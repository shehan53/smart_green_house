package com.greenhouse.fault;

/**
 * Author: Role 2 - Fault Tolerance Engineer
 * Concepts: #3 TMR voting result
 * Purpose: Immutable result of one vote: status, voted temperature, number of sensors used and a
 *          human-readable explanation for the log.
 */
public record VoteResult(VoteStatus status, double value, int sensorsUsed, String detail) {

    public static VoteResult waiting() {
        return new VoteResult(VoteStatus.WAITING, Double.NaN, 0, "no vote yet");
    }

    public boolean trustworthy() {
        return status == VoteStatus.OK || status == VoteStatus.MASKED || status == VoteStatus.DEGRADED;
    }
}
