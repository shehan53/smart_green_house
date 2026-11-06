package com.greenhouse.control;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Author: Role 4 - Control Engineer
 * Concepts: #1 Sensors & actuators
 * Purpose: The four actuators of the greenhouse in one place.
 */
public class Actuators {

    public final Actuator heater = new Actuator("HEATER");
    public final Actuator fan = new Actuator("FAN");
    public final Actuator pump = new Actuator("PUMP");
    public final Actuator alarm = new Actuator("ALARM");

    public void allOff(String reason) {
        heater.set(false, reason);
        fan.set(false, reason);
        pump.set(false, reason);
        alarm.set(false, reason);
    }

    public Map<String, Boolean> toMap() {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("heater", heater.isOn());
        m.put("fan", fan.isOn());
        m.put("pump", pump.isOn());
        m.put("alarm", alarm.isOn());
        return m;
    }

    /** Form for the status line, e.g. "Heater off Fan ON  Pump off Alarm off" (ON in capitals stands out). */
    public String summary() {
        return "Heater " + onOff(heater) + " Fan " + onOff(fan) + " Pump " + onOff(pump) + " Alarm " + onOff(alarm);
    }

    private static String onOff(Actuator a) {
        return a.isOn() ? "ON " : "off";
    }
}
