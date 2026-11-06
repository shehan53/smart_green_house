package com.greenhouse.sim;

/**
 * Author: Role 1 - Sensor Engineer
 * Concepts: #2 Fault -> error -> failure
 * Purpose: Faults that can be injected into a sensor on command (from Postman or the console menu).
 *          The FAULT is the broken sensor, the ERROR is the wrong/missing reading it produces,
 *          a FAILURE would be a wrong actuator action - which TMR, watchdog and safe state prevent.
 */
public enum FaultMode {
    /** Sensor works correctly. */
    NONE,
    /** Sensor adds a constant offset to the real value (F1: T2 +15 C). */
    WRONG_VALUE,
    /** Sensor keeps reporting the same (frozen) value although the greenhouse changes. */
    STUCK,
    /** Sensor stops sending readings completely (F2: T3 dead). */
    DEAD
}
