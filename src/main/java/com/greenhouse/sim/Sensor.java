package com.greenhouse.sim;

import com.greenhouse.buffer.BoundedBuffer;
import com.greenhouse.monitor.Log;
import com.greenhouse.monitor.TimingMonitor;

/**
 * Author: Role 1 - Sensor Engineer
 * Concepts: #1 Periodic task (every 500 ms, RT1 firm deadline), #2 Fault -> error -> failure,
 *           #6 Runnable run by a ScheduledExecutorService, #7 volatile (fault set by another thread)
 * Purpose: One simulated sensor (T1, T2, T3, H1 or S1). Each period it reads the greenhouse,
 *          applies its fault mode (wrong value / stuck / dead) and puts the reading into the
 *          bounded buffer (producer side of producer-consumer).
 */
public class Sensor implements Runnable {

    public static final long PERIOD_MS = 500;
    /** A sensor waits at most this long for space in the buffer, so it never misses its own period. */
    private static final long PUT_TIMEOUT_MS = 50;

    /** Fault configuration as ONE immutable object -> mode and value always change together. */
    private record FaultConfig(FaultMode mode, double value, long injectedAtMs) { }

    private final String id;
    private final SensorType type;
    private final GreenhouseSimulator simulator;
    private final BoundedBuffer buffer;
    private final TimingMonitor timing;

    // CONCEPT 7: volatile - written by the REST/menu thread, read by the sensor thread every 500 ms
    private volatile FaultConfig fault = new FaultConfig(FaultMode.NONE, 0, 0);
    private volatile double lastProduced = Double.NaN;
    private volatile double stuckAt = Double.NaN;

    public Sensor(String id, SensorType type, GreenhouseSimulator simulator, BoundedBuffer buffer, TimingMonitor timing) {
        this.id = id;
        this.type = type;
        this.simulator = simulator;
        this.buffer = buffer;
        this.timing = timing;
    }

    // CONCEPT 1 + 6: periodic task body, released every 500 ms by scheduleAtFixedRate
    @Override
    public void run() {
        long start = System.nanoTime();
        try {
            sampleOnce();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // shutdown in progress
        } catch (Exception e) {
            // an exception must never kill the periodic task (the executor would stop scheduling it)
            Log.error("SENSOR", id + " sampling failed - task continues next period", e);
        } finally {
            timing.record(TimingMonitor.Task.SENSOR, "sensor-" + id, start, System.nanoTime());
        }
    }

    private void sampleOnce() throws InterruptedException {
        FaultConfig f = fault; // read the volatile once -> consistent snapshot for this period
        if (f.mode() == FaultMode.DEAD) {
            return; // CONCEPT 2: fault = dead sensor -> error = missing reading (watchdog must notice)
        }
        double value = switch (f.mode()) {
            case WRONG_VALUE -> simulator.read(type) + f.value(); // CONCEPT 2: fault -> wrong reading (error)
            case STUCK -> stuckAt;                                 // frozen value
            default -> simulator.read(type);
        };
        lastProduced = value;
        boolean stored = buffer.put(new Reading(id, type, value, System.currentTimeMillis()), PUT_TIMEOUT_MS);
        if (!stored) {
            Log.warn("BUFFER", "Buffer full - reading of " + id + " dropped (sensor does not block past its deadline)");
        }
    }

    /** Called from the REST API or the console menu (another thread). */
    public synchronized void injectFault(FaultMode mode, double value) {
        if (mode == FaultMode.STUCK) {
            stuckAt = Double.isNaN(lastProduced) ? simulator.read(type) : lastProduced;
        }
        fault = new FaultConfig(mode, value, System.currentTimeMillis()); // one volatile write publishes all
        switch (mode) {
            case NONE -> Log.info("FAULT", id + " repaired - sensor works normally again");
            case WRONG_VALUE -> Log.warn("FAULT", "FAULT injected: " + id + " -> WRONG_VALUE ("
                    + (value >= 0 ? "+" : "") + Log.f1(value) + " " + type.unit() + ")");
            case STUCK -> Log.warn("FAULT", "FAULT injected: " + id + " -> STUCK at " + Log.f1(stuckAt) + " " + type.unit());
            case DEAD -> Log.warn("FAULT", "FAULT injected: " + id + " -> DEAD (stops sending readings)");
        }
    }

    public void repair() {
        if (fault.mode() != FaultMode.NONE) {
            injectFault(FaultMode.NONE, 0);
        }
    }

    public String getId() { return id; }

    public SensorType getType() { return type; }

    public FaultMode getFaultMode() { return fault.mode(); }

    public long getFaultInjectedAtMs() { return fault.injectedAtMs(); }

    public double getLastProduced() { return lastProduced; }
}
