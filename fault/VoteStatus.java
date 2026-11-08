package com.greenhouse.fault;

/**
 * Author: Role 2 - Fault Tolerance Engineer
 * Concepts: #3 TMR with graceful degradation
 * Purpose: Result status of the TMR voter.
 */
public enum VoteStatus {
    /** No vote yet (system just started). */
    WAITING,
    /** All 3 temperature sensors agree. */
    OK,
    /** 3 sensors, one disagrees and is out-voted (fault masked). */
    MASKED,
    /** Only 2 sensors left (one failed) and they agree - graceful degradation. */
    DEGRADED,
    /** No agreement / fewer than 2 sensors - temperature cannot be trusted -> SAFE STATE. */
    FAILED
}
