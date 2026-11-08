package com.greenhouse.fault;

import com.greenhouse.buffer.LatestReadings;
import com.greenhouse.monitor.Log;
import com.greenhouse.monitor.TimingMonitor;
import com.greenhouse.sim.FaultMode;
import com.greenhouse.sim.Sensor;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Author: Role 2 - Fault Tolerance Engineer
 * Concepts: #4 Watchdog / heartbeat, #1 hard deadline RT3 (dead sensor detected within 2.5 s)
 * Purpose: Runs every 1 s. Every reading that arrives is a heartbeat. If a sensor's last heartbeat
 *          is older than 1.5 s it is marked FAILED; when heartbeats return it is marked RECOVERED.
 *          Worst-case detection = timeout 1.5 s + check period 1.0 s = 2.5 s (meets RT3).
 */
public class Watchdog implements Runnable {

    public static final long PERIOD_MS = 1000;
    public static final long TIMEOUT_MS = 1500;
    public static final long RT3_DEADLINE_MS = 2500;

    private final Collection<Sensor> sensors;
    private final LatestReadings latest;
    private final TimingMonitor timing;

    private final Map<String, Boolean> failed = new HashMap<>();
    private final Map<String, Long> detectionLatencyMs = new LinkedHashMap<>();

    public Watchdog(Collection<Sensor> sensors, LatestReadings latest, TimingMonitor timing) {
        this.sensors = sensors;
        this.latest = latest;
        this.timing = timing;
    }

    @Override
    public void run() {
        long start = System.nanoTime();
        try {
            check();
        } catch (Exception e) {
            Log.error("WATCHDOG", "Watchdog check failed - continues next period", e);
        } finally {
            timing.record(TimingMonitor.Task.WATCHDOG, "watchdog", start, System.nanoTime());
        }
    }

    private void check() {
        long now = System.currentTimeMillis();
        for (Sensor s : sensors) {
            String id = s.getId();
            long age = now - latest.lastHeartbeat(id); // CONCEPT 4: time since last heartbeat
            boolean wasFailed = isFailed(id);

            if (age > TIMEOUT_MS && !wasFailed) {
                setFailed(id, true);
                Log.error("WATCHDOG", id + " FAILED - no heartbeat for " + age + " ms (timeout " + TIMEOUT_MS + " ms)");
                if (s.getFaultMode() == FaultMode.DEAD) {
                    long latency = now - s.getFaultInjectedAtMs();
                    synchronized (this) {
                        detectionLatencyMs.put(id, latency);
                    }
                    // worst case = TIMEOUT 1500 ms + PERIOD 1000 ms = 2500 ms = exactly the deadline (zero margin),
                    // so a few ms of GC / OS scheduling jitter can make it late
                    String verdict = latency <= RT3_DEADLINE_MS ? "MET"
                            : "MISSED by " + (latency - RT3_DEADLINE_MS) + " ms (zero-margin worst case 1.5 s + 1.0 s, Java/OS jitter)";
                    Log.warn("RT3", "[RT3 HARD] " + id + " detected dead " + latency + " ms after it stopped -> "
                            + verdict + " (deadline " + RT3_DEADLINE_MS + " ms)");
                }
            } else if (age <= TIMEOUT_MS && wasFailed) {
                setFailed(id, false);
                Log.info("WATCHDOG", id + " RECOVERED - heartbeats are arriving again");
            }
        }
    }

    public synchronized boolean isHealthy(String id) {
        return !failed.getOrDefault(id, false);
    }

    private synchronized boolean isFailed(String id) {
        return failed.getOrDefault(id, false);
    }

    private synchronized void setFailed(String id, boolean value) {
        failed.put(id, value);
    }

    public synchronized Map<String, Long> getDetectionLatencies() {
        return new LinkedHashMap<>(detectionLatencyMs);
    }

    public synchronized void reset() {
        failed.clear();
        detectionLatencyMs.clear();
    }
}
