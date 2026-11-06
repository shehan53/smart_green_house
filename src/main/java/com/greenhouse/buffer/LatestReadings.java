package com.greenhouse.buffer;

import com.greenhouse.sim.Reading;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Author: Role 3 - Concurrency Engineer
 * Concepts: #8 synchronized methods (shared data), #4 heartbeat time per sensor
 * Purpose: Latest VALID reading of each sensor and the time of the last heartbeat (any reading
 *          that arrived). Written by the consumer thread, read by the controller, watchdog,
 *          status display and REST threads - so every method is synchronized.
 */
public class LatestReadings {

    private final Map<String, Reading> latestValid = new HashMap<>();
    private final Map<String, Long> lastHeartbeatMs = new HashMap<>();

    /** Any reading that arrives proves the sensor is alive (heartbeat), even if its value is invalid. */
    public synchronized void heartbeat(String sensorId, long timeMs) {
        lastHeartbeatMs.put(sensorId, timeMs);
    }

    public synchronized void accept(Reading reading) {
        latestValid.put(reading.sensorId(), reading);
    }

    /** An invalid reading makes the sensor untrusted: its old value must not be used any more. */
    public synchronized void reject(String sensorId) {
        latestValid.remove(sensorId);
    }

    public synchronized long lastHeartbeat(String sensorId) {
        return lastHeartbeatMs.getOrDefault(sensorId, 0L);
    }

    /** Value of the last valid reading, or null if there is none or it is older than maxAgeMs. */
    public synchronized Double freshValue(String sensorId, long nowMs, long maxAgeMs) {
        Reading r = latestValid.get(sensorId);
        if (r == null || nowMs - r.timestampMs() > maxAgeMs) return null;
        return r.value();
    }

    /** Clears everything and treats all sensors as "just seen" (used at start-up and reset). */
    public synchronized void resetAll(Collection<String> sensorIds, long nowMs) {
        latestValid.clear();
        for (String id : sensorIds) lastHeartbeatMs.put(id, nowMs);
    }
}
