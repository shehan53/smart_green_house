package com.greenhouse.monitor;

import com.greenhouse.control.Controller;
import com.greenhouse.sim.FaultMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Author: Role 5 - Timing & Integration Lead
 * Concepts: #1 Real-time system - makes every input (stimulus) and the expected response visible
 * Purpose: Translates an input (Postman request or menu key) into plain words for the
 *          "NEW INPUT RECEIVED" box: WHAT IT MEANS and what the system is EXPECTED to do.
 */
public final class InputDescriber {

    private InputDescriber() { }

    public static String environmentMeaning(double t, double h, double s) {
        return String.format(Locale.US, "the greenhouse now has T=%.1f C, humidity=%.1f %%, soil=%.1f %%", t, h, s);
    }

    public static String environmentExpected(double t, double h, double s) {
        List<String> e = new ArrayList<>();
        if (t < Controller.HEATER_ON_BELOW) e.add("heater ON (T < 22 C)");
        else if (t > Controller.HEATER_OFF_ABOVE) e.add("heater OFF (T > 24 C)");
        else e.add("heater keeps its state (22-24 C hysteresis)");
        e.add(t > Controller.FAN_ON_ABOVE_TEMP || h > Controller.FAN_ON_ABOVE_HUMIDITY
                ? "fan ON (T > 28 C or H > 85 %)" : "fan OFF");
        if (s < Controller.PUMP_ON_BELOW) e.add("pump ON (soil < 35 %)");
        else if (s > Controller.PUMP_OFF_ABOVE) e.add("pump OFF (soil > 50 %)");
        else e.add("pump keeps its state (35-50 % hysteresis)");
        if (t > Controller.ALARM_ABOVE) e.add("ALARM ON within 1 s (T > 35 C)");
        return "sensors read it within 0.5 s, controller reacts within 1 s: " + String.join(", ", e);
    }

    public static String heatWaveMeaning(double delta) {
        return String.format(Locale.US, "heat wave: the real temperature changes by %+.1f C", delta);
    }

    public static String heatWaveExpected() {
        return "if the temperature goes above 35 C: Observe & React -> alarm + fan ON within 1 s (RT2)";
    }

    public static String faultMeaning(String id, FaultMode type, Double value) {
        String sensor = sensorName(id);
        double v = value == null ? 0 : value;
        return switch (type) {
            case WRONG_VALUE -> String.format(Locale.US, "%s will report a WRONG value (%+.1f added to the real value)", sensor, v);
            case STUCK -> sensor + " gets STUCK (keeps reporting its last value)";
            case DEAD -> sensor + " is DEAD (stops sending readings)";
            case NONE -> sensor + " is repaired";
        };
    }

    public static String faultExpected(String id, FaultMode type) {
        String up = id.toUpperCase();
        if (type == FaultMode.NONE) return repairExpected(up);
        if (up.startsWith("T")) {
            return type == FaultMode.DEAD
                    ? "watchdog marks " + up + " FAILED within 2.5 s -> voter DEGRADED (2 sensors); SAFE STATE if the other two disagree"
                    : "voter out-votes " + up + " -> MASKED, no wrong action; impossible values are REJECTED; "
                      + "SAFE STATE if a second temperature sensor is also bad";
        }
        if (up.equals("H1")) {
            return type == FaultMode.DEAD
                    ? "watchdog marks H1 FAILED within 2.5 s -> fan ON as precaution, warning every second"
                    : "humidity value is wrong (no redundancy); values outside 0..100 % are REJECTED -> fan ON as precaution";
        }
        return type == FaultMode.DEAD
                ? "watchdog marks S1 FAILED within 2.5 s -> pump OFF (no flooding), warning every second"
                : "soil value is wrong (no redundancy); values outside 0..100 % are REJECTED -> pump OFF";
    }

    public static String repairExpected(String id) {
        return id.toUpperCase() + " works again -> watchdog RECOVERED / voter back to OK, safe state left after 2 good votes";
    }

    public static String sensorName(String id) {
        String up = id.toUpperCase();
        if (up.startsWith("T")) return "temperature sensor " + up;
        if (up.equals("H1")) return "humidity sensor H1";
        return "soil-moisture sensor " + up;
    }
}
