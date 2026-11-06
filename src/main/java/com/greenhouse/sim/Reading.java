package com.greenhouse.sim;

/**
 * Author: Role 1 - Sensor Engineer
 * Concepts: #1 Sensors (one sample produced by a periodic sensor task)
 * Purpose: Immutable sensor reading. Immutable objects can be passed between threads safely
 *          (producer sensor thread -> bounded buffer -> consumer thread).
 */
public record Reading(String sensorId, SensorType type, double value, long timestampMs) {
}
