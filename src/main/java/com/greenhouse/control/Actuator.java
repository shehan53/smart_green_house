package com.greenhouse.control;

import com.greenhouse.monitor.Log;

/**
 * Author: Role 4 - Control Engineer
 * Concepts: #1 Actuators, #7 volatile flag (switched by the controller, read by display/REST threads)
 * Purpose: One simulated actuator (heater, fan, pump or alarm). Every change is logged with a
 *          millisecond timestamp and the reason for the change.
 */
public class Actuator {

    private final String name;
    private volatile boolean on;          // CONCEPT 7: visible to all threads immediately
    private volatile long lastChangeMs;

    public Actuator(String name) {
        this.name = name;
    }

    /** @return true if the state really changed */
    public synchronized boolean set(boolean newState, String reason) {
        if (on == newState) return false;
        on = newState;
        lastChangeMs = System.currentTimeMillis();
        Log.info("ACTUATOR", String.format("%-6s -> %-3s (%s)", name, newState ? "ON" : "OFF", reason));
        return true;
    }

    public boolean isOn() { return on; }

    public String getName() { return name; }

    public long getLastChangeMs() { return lastChangeMs; }
}
