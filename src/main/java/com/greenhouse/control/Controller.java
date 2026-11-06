package com.greenhouse.control;

import com.greenhouse.buffer.LatestReadings;
import com.greenhouse.fault.SafeStateManager;
import com.greenhouse.fault.TmrVoter;
import com.greenhouse.fault.VoteResult;
import com.greenhouse.fault.VoteStatus;
import com.greenhouse.fault.Watchdog;
import com.greenhouse.monitor.Log;
import com.greenhouse.monitor.TimingMonitor;
import com.greenhouse.sim.GreenhouseSimulator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Author: Role 4 - Control Engineer
 * Concepts: #10 Environmental Control and Observe & React, #1 periodic task with firm deadline RT4
 *           and hard deadline RT2, #5 control cycle wrapped in try/catch -> safe state (forward recovery)
 * Purpose: Every 1 s: vote the temperature, then switch the actuators:
 *            heater ON below 22 C, OFF above 24 C (hysteresis)
 *            fan    ON above 28 C or humidity above 85 %
 *            pump   ON below 35 % soil moisture, OFF above 50 %
 *            alarm  ON above 35 C
 *          Observe & React: every new temperature reading is checked immediately so the alarm
 *          switches ON within 1 s of the temperature going above 35 C (RT2).
 */
public class Controller implements Runnable {

    public static final long PERIOD_MS = 1000;
    public static final double HEATER_ON_BELOW = 22.0;
    public static final double HEATER_OFF_ABOVE = 24.0;
    public static final double FAN_ON_ABOVE_TEMP = 28.0;
    public static final double FAN_ON_ABOVE_HUMIDITY = 85.0;
    public static final double PUMP_ON_BELOW = 35.0;
    public static final double PUMP_OFF_ABOVE = 50.0;
    public static final double ALARM_ABOVE = 35.0;
    public static final long RT2_DEADLINE_MS = 1000;

    private final List<String> temperatureIds;
    private final String humidityId;
    private final String soilId;
    private final LatestReadings latest;
    private final Watchdog watchdog;
    private final TmrVoter voter;
    private final SafeStateManager safeState;
    private final Actuators actuators;
    private final GreenhouseSimulator simulator; // only used to MEASURE the RT2 reaction time
    private final TimingMonitor timing;

    // CONCEPT 7: volatile results read by the status display and REST threads
    private volatile VoteResult lastVote = VoteResult.waiting();
    private volatile Double lastHumidity;
    private volatile Double lastSoil;
    private volatile long lastRt2ReactionMs = -1;
    private long rt2ReportedFor = -1;
    // CONCEPT 7: atomic one-shot flag, set by the REST thread, consumed by the control thread
    private final AtomicBoolean injectException = new AtomicBoolean(false);

    public Controller(List<String> temperatureIds, String humidityId, String soilId, LatestReadings latest,
                      Watchdog watchdog, TmrVoter voter, SafeStateManager safeState, Actuators actuators,
                      GreenhouseSimulator simulator, TimingMonitor timing) {
        this.temperatureIds = temperatureIds;
        this.humidityId = humidityId;
        this.soilId = soilId;
        this.latest = latest;
        this.watchdog = watchdog;
        this.voter = voter;
        this.safeState = safeState;
        this.actuators = actuators;
        this.simulator = simulator;
        this.timing = timing;
    }

    // CONCEPT 5: the whole control cycle is wrapped in try/catch -> any exception leads to the SAFE STATE
    //            and the periodic task keeps running next second (forward recovery, no crash)
    @Override
    public void run() {
        long start = System.nanoTime();
        try {
            controlCycle();
        } catch (Exception e) {
            Log.error("CONTROL", "Exception in control cycle: " + e.getMessage() + " -> forward recovery to SAFE STATE", e);
            safeState.enter("exception in control cycle: " + e.getMessage());
        } finally {
            timing.record(TimingMonitor.Task.CONTROL, "controller", start, System.nanoTime());
        }
    }

    // CONCEPT 10: Environmental Control - periodic sense -> decide -> act loop
    private synchronized void controlCycle() {
        if (injectException.getAndSet(false)) {
            throw new IllegalStateException("simulated software fault (injected for testing)");
        }
        long now = System.currentTimeMillis();

        VoteResult vote = voter.vote(trustedTemperatures(now));
        logVoteChange(lastVote, vote);
        lastVote = vote;

        Double humidity = trustedValue(humidityId, now);
        Double soil = trustedValue(soilId, now);
        lastHumidity = humidity;
        lastSoil = soil;

        controlPump(soil); // soil control does not depend on the temperature

        if (vote.status() == VoteStatus.FAILED) {
            safeState.enter("no trustworthy temperature: " + vote.detail());
            safeState.tick();
            return;
        }
        if (safeState.goodVote()) {   // still confirming the data -> stay safe this cycle
            safeState.tick();
            return;
        }
        controlClimate(vote.value(), humidity);
    }

    private void controlClimate(double t, Double humidity) {
        // heater with hysteresis: between 22 and 24 C the heater keeps its state
        if (t < HEATER_ON_BELOW) {
            actuators.heater.set(true, "T=" + Log.f1(t) + " C < " + HEATER_ON_BELOW + " C");
        } else if (t > HEATER_OFF_ABOVE) {
            actuators.heater.set(false, "T=" + Log.f1(t) + " C > " + HEATER_OFF_ABOVE + " C");
        }

        boolean fanOn;
        String fanReason;
        if (humidity == null) {
            fanOn = true;
            fanReason = "humidity sensor " + humidityId + " not trustworthy - fan ON as precaution";
            Log.warn("CONTROL", "WARNING " + fanReason);
        } else {
            fanOn = t > FAN_ON_ABOVE_TEMP || humidity > FAN_ON_ABOVE_HUMIDITY;
            fanReason = "T=" + Log.f1(t) + " C, H=" + Log.f1(humidity) + " %";
        }
        actuators.fan.set(fanOn, fanReason);

        boolean alarmOn = t > ALARM_ABOVE;
        if (actuators.alarm.set(alarmOn, "T=" + Log.f1(t) + " C " + (alarmOn ? ">" : "<=") + " " + ALARM_ABOVE + " C")
                && alarmOn) {
            reportRt2("control cycle");
        }
    }

    private void controlPump(Double soil) {
        if (soil == null) {
            Log.warn("CONTROL", "WARNING soil sensor " + soilId + " not trustworthy - pump OFF (prevents flooding)");
            actuators.pump.set(false, "soil sensor failed - fail-safe");
        } else if (soil < PUMP_ON_BELOW) {
            actuators.pump.set(true, "soil=" + Log.f1(soil) + " % < " + PUMP_ON_BELOW + " %");
        } else if (soil > PUMP_OFF_ABOVE) {
            actuators.pump.set(false, "soil=" + Log.f1(soil) + " % > " + PUMP_OFF_ABOVE + " %");
        }
    }

    /**
     * CONCEPT 10: Observe & React - called by the consumer thread for every new temperature reading.
     * Without this fast path the worst case would be 500 ms (sensor) + 1000 ms (control period) = 1.5 s,
     * which would MISS the hard 1 s deadline of RT2.
     */
    public synchronized void onTemperatureReading() {
        try {
            if (safeState.isActive() || actuators.alarm.isOn()) return;
            long now = System.currentTimeMillis();
            VoteResult v = voter.vote(trustedTemperatures(now));
            if (v.trustworthy() && v.value() > ALARM_ABOVE) {
                Log.warn("OBSERVE", "Observe & React: voted T=" + Log.f1(v.value())
                        + " C > 35 C -> immediate reaction (not waiting for the next control cycle)");
                lastVote = v; // the fresh vote is published at once (status shows the new temperature)
                actuators.alarm.set(true, "T=" + Log.f1(v.value()) + " C > " + ALARM_ABOVE + " C");
                actuators.fan.set(true, "over-temperature");
                actuators.heater.set(false, "over-temperature");
                reportRt2("observe & react");
            }
        } catch (Exception e) {
            Log.error("OBSERVE", "Error in Observe & React - the control cycle still protects the greenhouse", e);
        }
    }

    private void reportRt2(String path) {
        long since = simulator.getAboveAlarmSinceMs();
        if (since < 0 || since == rt2ReportedFor) return;
        rt2ReportedFor = since;
        long ms = System.currentTimeMillis() - since;
        lastRt2ReactionMs = ms;
        Log.warn("RT2", "[RT2 HARD] alarm ON " + ms + " ms after temperature went above 35 C (via " + path + ") -> "
                + (ms <= RT2_DEADLINE_MS ? "MET" : "MISSED") + " (deadline " + RT2_DEADLINE_MS + " ms)");
    }

    /** Only sensors the watchdog considers alive AND with a fresh, valid reading take part in the vote. */
    private Map<String, Double> trustedTemperatures(long now) {
        Map<String, Double> inputs = new LinkedHashMap<>();
        for (String id : temperatureIds) {
            Double v = trustedValue(id, now);
            if (v != null) inputs.put(id, v);
        }
        return inputs;
    }

    private Double trustedValue(String id, long now) {
        if (!watchdog.isHealthy(id)) return null;
        return latest.freshValue(id, now, Watchdog.TIMEOUT_MS);
    }

    private void logVoteChange(VoteResult previous, VoteResult current) {
        if (previous.status() == current.status() && previous.sensorsUsed() == current.sensorsUsed()) return;
        String msg = previous.status() + " -> " + current.status() + " : " + current.detail()
                + (current.trustworthy() ? " -> voted T=" + Log.f1(current.value()) + " C" : "");
        if (current.status() == VoteStatus.OK) {
            Log.info("VOTER", msg);
        } else {
            Log.warn("VOTER", msg);
        }
    }

    /** Next control cycle throws an exception (used to demonstrate concept 5). */
    public void injectException() {
        injectException.set(true);
        Log.warn("FAULT", "FAULT injected: software exception in the next control cycle");
    }

    public synchronized void reset() {
        lastVote = VoteResult.waiting();
        lastRt2ReactionMs = -1;
        rt2ReportedFor = -1;
        injectException.set(false);
        safeState.reset();
        actuators.allOff("system reset");
    }

    public VoteResult getLastVote() { return lastVote; }

    public Double getLastHumidity() { return lastHumidity; }

    public Double getLastSoil() { return lastSoil; }

    public long getLastRt2ReactionMs() { return lastRt2ReactionMs; }
}
