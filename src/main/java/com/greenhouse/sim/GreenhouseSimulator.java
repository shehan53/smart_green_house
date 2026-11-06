package com.greenhouse.sim;

import com.greenhouse.monitor.Log;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Author: Role 1 - Sensor Engineer
 * Concepts: #8 synchronized methods (shared by sensor threads, the plant task and REST/menu threads),
 *           #1 Sensors & actuators (the "physical world" the controller observes and acts on)
 * Purpose: Simple greenhouse simulation: the sun slowly warms the air, the heater warms,
 *          the fan cools and dries the air, the pump wets the soil, the soil slowly dries.
 *          Postman / the menu can set values directly or add a heat wave.
 */
public class GreenhouseSimulator {

    public static final double NORMAL_TEMPERATURE = 25.0;
    public static final double NORMAL_HUMIDITY = 60.0;
    public static final double NORMAL_SOIL = 45.0;
    private static final double ALARM_LIMIT = 35.0;

    private double temperature;
    private double humidity;
    private double soil;
    /** When the TRUE temperature went above 35 C - used only to MEASURE the RT2 reaction time. */
    private long aboveAlarmSinceMs = -1;
    private final Random noise = new Random();

    public GreenhouseSimulator() {
        reset();
    }

    // CONCEPT 8: synchronized - temperature, humidity and soil always change together consistently
    public synchronized void reset() {
        double old = temperature;
        temperature = NORMAL_TEMPERATURE;
        humidity = NORMAL_HUMIDITY;
        soil = NORMAL_SOIL;
        trackAlarmCrossing(old);
    }

    public synchronized void setEnvironment(double t, double h, double s) {
        double old = temperature;
        temperature = clamp(t, -40, 80);
        humidity = clamp(h, 0, 100);
        soil = clamp(s, 0, 100);
        trackAlarmCrossing(old);
        Log.info("SIMULATOR", "Environment set: T=" + Log.f1(temperature) + " C, H=" + Log.f1(humidity)
                + " %, Soil=" + Log.f1(soil) + " %");
    }

    public synchronized void addHeatWave(double delta) {
        double old = temperature;
        temperature = clamp(temperature + delta, -40, 80);
        trackAlarmCrossing(old);
        Log.warn("SIMULATOR", "HEAT WAVE " + (delta >= 0 ? "+" : "") + Log.f1(delta) + " C -> real temperature now "
                + Log.f1(temperature) + " C");
    }

    /** Physics for one second, depending on which actuators are ON. */
    public synchronized void step(boolean heaterOn, boolean fanOn, boolean pumpOn) {
        double old = temperature;
        temperature += 0.01;                 // sun slowly warms the greenhouse
        if (heaterOn) temperature += 0.5;    // heater warms
        if (fanOn) temperature -= 0.5;       // fan cools
        humidity += 0.1;                     // plants release moisture
        if (fanOn) humidity -= 1.0;          // fan dries the air
        soil -= 0.1;                         // soil slowly dries out
        if (pumpOn) soil += 2.0;             // pump waters the soil
        temperature = clamp(temperature, -40, 80);
        humidity = clamp(humidity, 0, 100);
        soil = clamp(soil, 0, 100);
        trackAlarmCrossing(old);
    }

    /** What a healthy sensor would measure right now (true value + small measurement noise). */
    public synchronized double read(SensorType type) {
        return switch (type) {
            case TEMPERATURE -> temperature + (noise.nextDouble() - 0.5) * 0.4; // +/-0.2 C
            case HUMIDITY -> clamp(humidity + (noise.nextDouble() - 0.5), 0, 100);
            case SOIL -> clamp(soil + (noise.nextDouble() - 0.5), 0, 100);
        };
    }

    public synchronized long getAboveAlarmSinceMs() {
        return aboveAlarmSinceMs;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("temperature", round1(temperature));
        m.put("humidity", round1(humidity));
        m.put("soil", round1(soil));
        return m;
    }

    private void trackAlarmCrossing(double oldTemperature) {
        if (temperature > ALARM_LIMIT && (oldTemperature <= ALARM_LIMIT || aboveAlarmSinceMs < 0)) {
            aboveAlarmSinceMs = System.currentTimeMillis();
        } else if (temperature <= ALARM_LIMIT) {
            aboveAlarmSinceMs = -1;
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
