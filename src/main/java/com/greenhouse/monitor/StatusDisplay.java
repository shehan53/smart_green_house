package com.greenhouse.monitor;

import com.greenhouse.buffer.BoundedBuffer;
import com.greenhouse.control.Actuators;
import com.greenhouse.control.Controller;
import com.greenhouse.fault.SafeStateManager;
import com.greenhouse.fault.VoteResult;
import com.greenhouse.fault.Watchdog;
import com.greenhouse.sim.FaultMode;
import com.greenhouse.sim.Sensor;

import java.util.Collection;

/**
 * Author: Role 5 - Timing & Integration Lead
 * Concepts: #1 Periodic task with a SOFT deadline (RT5: status line every 2 s)
 * Purpose: Prints one status line: temperature, vote status, humidity, soil, actuators,
 *          safe state, sensor health and buffer fill level.
 */
public class StatusDisplay implements Runnable {

    public static final long PERIOD_MS = 2000;

    private final Controller controller;
    private final Actuators actuators;
    private final SafeStateManager safeState;
    private final Watchdog watchdog;
    private final Collection<Sensor> sensors;
    private final BoundedBuffer buffer;
    private final TimingMonitor timing;

    public StatusDisplay(Controller controller, Actuators actuators, SafeStateManager safeState, Watchdog watchdog,
                         Collection<Sensor> sensors, BoundedBuffer buffer, TimingMonitor timing) {
        this.controller = controller;
        this.actuators = actuators;
        this.safeState = safeState;
        this.watchdog = watchdog;
        this.sensors = sensors;
        this.buffer = buffer;
        this.timing = timing;
    }

    @Override
    public void run() {
        long start = System.nanoTime();
        try {
            printNow();
        } catch (Exception e) {
            Log.error("STATUS", "Status display failed - continues next period", e);
        } finally {
            timing.record(TimingMonitor.Task.DISPLAY, "display", start, System.nanoTime());
        }
    }

    public void printNow() {
        VoteResult v = controller.getLastVote();
        String temp = v.trustworthy() ? Log.f1(v.value()) + "C" : "--.-C";
        Double h = controller.getLastHumidity();
        Double s = controller.getLastSoil();

        // only sensors with a problem are listed, so the line stays short
        StringBuilder problems = new StringBuilder();
        for (Sensor sensor : sensors) {
            boolean healthy = watchdog.isHealthy(sensor.getId());
            if (!healthy || sensor.getFaultMode() != FaultMode.NONE) {
                problems.append(sensor.getId()).append(':')
                        .append(healthy ? sensor.getFaultMode().name() : "FAILED").append(' ');
            }
        }
        String sensorText = problems.isEmpty() ? "sensors ok" : problems.toString().trim();

        Log.info("STATUS", String.format("(every 2 s) T=%-6s [%-8s %d/3] H=%s%% S=%s%% | %s | SAFE %s | %s | buf %d/%d",
                temp, v.status(), v.sensorsUsed(),
                h == null ? "--.-" : Log.f1(h), s == null ? "--.-" : Log.f1(s),
                actuators.summary(), safeState.isActive() ? "YES" : "no ",
                sensorText, buffer.size(), buffer.capacity()));
    }
}
