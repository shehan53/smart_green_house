package com.greenhouse.sim;

/**
 * Author: Role 1 - Sensor Engineer
 * Concepts: #1 Sensors & actuators
 * Purpose: The three kinds of simulated sensors in the greenhouse.
 */
public enum SensorType {
    TEMPERATURE("C"),
    HUMIDITY("%"),
    SOIL("%");

    private final String unit;

    SensorType(String unit) { this.unit = unit; }

    public String unit() { return unit; }
}
