package com.greenhouse.fault;

import com.greenhouse.control.Actuators;
import com.greenhouse.monitor.Log;

/**
 * Author: Role 2 - Fault Tolerance Engineer
 * Concepts: #2 Fail-safe state, #5 Exception handling to a safe state (forward recovery)
 * Purpose: When the temperature cannot be trusted, or an exception occurs, the greenhouse goes to
 *          the SAFE STATE: heater OFF, fan ON, alarm ON (plants cannot be "cooked").
 *          It is left automatically after 2 consecutive good votes. If it lasts longer than 30 s a
 *          CRITICAL warning is repeated every 2 s. The system never stops itself.
 */
public class SafeStateManager {

    public static final int GOOD_VOTES_TO_EXIT = 2;
    public static final long ESCALATE_AFTER_MS = 30_000;
    private static final long CRITICAL_REPEAT_MS = 2_000;

    private final Actuators actuators;

    private volatile boolean active; // CONCEPT 7: read by display / REST threads without locking
    private String reason;
    private String lastReason;
    private long enteredAtMs;
    private int goodVotes;
    private int enterCount;
    private long lastCriticalLogMs;

    public SafeStateManager(Actuators actuators) {
        this.actuators = actuators;
    }

    // CONCEPT 2: fail-safe - a known safe output instead of acting on untrusted data
    public synchronized void enter(String why) {
        goodVotes = 0;
        if (!active) {
            active = true;
            reason = why;
            lastReason = why;
            enteredAtMs = System.currentTimeMillis();
            enterCount++;
            Log.error("SAFE-STATE", "ENTER SAFE STATE - " + why);
            Log.error("SAFE-STATE", "Failure prevented: heater OFF, fan ON, alarm ON until good data returns");
        }
        applySafeOutputs();
    }

    /**
     * Called by the controller after a trustworthy vote.
     * @return true if the system is (still) in the safe state after this vote
     */
    public synchronized boolean goodVote() {
        if (!active) return false;
        goodVotes++;
        if (goodVotes >= GOOD_VOTES_TO_EXIT) {
            active = false;
            long seconds = (System.currentTimeMillis() - enteredAtMs) / 1000;
            Log.info("SAFE-STATE", "EXIT SAFE STATE after " + GOOD_VOTES_TO_EXIT + " consecutive good votes (was active "
                    + seconds + " s) - normal control resumes");
            reason = null;
            return false;
        }
        Log.info("SAFE-STATE", "Good vote " + goodVotes + "/" + GOOD_VOTES_TO_EXIT + " - confirming data before leaving safe state");
        applySafeOutputs();
        return true;
    }

    /** Called every control cycle: repeated alarm warning and escalation. */
    public synchronized void tick() {
        if (!active) return;
        long now = System.currentTimeMillis();
        long duration = now - enteredAtMs;
        Log.warn("ALARM", "SAFE STATE ACTIVE for " + duration / 1000 + " s - reason: " + reason);
        if (duration > ESCALATE_AFTER_MS && now - lastCriticalLogMs >= CRITICAL_REPEAT_MS) {
            lastCriticalLogMs = now;
            Log.critical("ALARM", "CRITICAL - safe state for more than " + ESCALATE_AFTER_MS / 1000
                    + " s - MANUAL INTERVENTION REQUIRED");
        }
    }

    /** Used at shutdown: leave the actuators in the safe position. */
    public synchronized void applyForShutdown() {
        Log.warn("SAFE-STATE", "Shutdown - actuators set to safe position");
        applySafeOutputs();
    }

    private void applySafeOutputs() {
        actuators.heater.set(false, "safe state");
        actuators.fan.set(true, "safe state");
        actuators.alarm.set(true, "safe state");
    }

    public synchronized void reset() {
        active = false;
        goodVotes = 0;
        reason = null;
    }

    public boolean isActive() { return active; }

    public synchronized String getReason() { return reason; }

    public synchronized String getLastReason() { return lastReason; }

    public synchronized int getEnterCount() { return enterCount; }
}
