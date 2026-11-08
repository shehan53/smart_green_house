package com.greenhouse.buffer;

import com.greenhouse.control.Controller;
import com.greenhouse.fault.ReadingValidator;
import com.greenhouse.monitor.Log;
import com.greenhouse.sim.Reading;
import com.greenhouse.sim.SensorType;

import java.util.HashMap;
import java.util.Map;

/**
 * Author: Role 3 - Concurrency Engineer
 * Concepts: #9 Consumer of the monitor bounded buffer, #6 Runnable in its own Thread with
 *           interrupt-based clean shutdown, #4 heartbeat recording
 * Purpose: Takes readings out of the buffer, records the heartbeat, validates the value,
 *          stores valid readings and notifies the controller (Observe & React fast path).
 */
public class ReadingConsumer implements Runnable {

    private static final long REJECT_LOG_INTERVAL_MS = 2000;

    private final BoundedBuffer buffer;
    private final LatestReadings latest;
    private final ReadingValidator validator;
    private final Controller controller;
    /** Only used by the consumer thread itself -> no synchronization needed (thread confinement). */
    private final Map<String, Long> lastRejectLogMs = new HashMap<>();

    public ReadingConsumer(BoundedBuffer buffer, LatestReadings latest, ReadingValidator validator, Controller controller) {
        this.buffer = buffer;
        this.latest = latest;
        this.validator = validator;
        this.controller = controller;
    }

    // CONCEPT 6: thread loop that ends cleanly when the thread is interrupted
    @Override
    public void run() {
        Log.info("CONSUMER", "Consumer thread started - waiting for sensor readings");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Reading reading = buffer.take(); // CONCEPT 9: blocks (wait) while the buffer is empty
                process(reading);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // keep the flag -> loop ends
            } catch (Exception e) {
                Log.error("CONSUMER", "Error while processing a reading - consumer keeps running", e);
            }
        }
        Log.info("CONSUMER", "Consumer thread stopped (interrupted) - clean shutdown");
    }

    private void process(Reading r) {
        latest.heartbeat(r.sensorId(), r.timestampMs()); // CONCEPT 4: every arrival is a heartbeat

        String problem = validator.validate(r);
        if (problem != null) {
            latest.reject(r.sensorId());
            long now = System.currentTimeMillis();
            long last = lastRejectLogMs.getOrDefault(r.sensorId(), 0L);
            if (now - last >= REJECT_LOG_INTERVAL_MS) {
                lastRejectLogMs.put(r.sensorId(), now);
                Log.warn("VALIDATION", "Reading of " + r.sensorId() + " = " + Log.f1(r.value()) + " "
                        + r.type().unit() + " REJECTED (" + problem + ") - sensor not trusted");
            }
            return;
        }
        latest.accept(r);
        if (r.type() == SensorType.TEMPERATURE) {
            controller.onTemperatureReading(); // CONCEPT 10: Observe & React (do not wait for the 1 s cycle)
        }
    }
}
