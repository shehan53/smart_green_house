package com.greenhouse.kernel;

/**
 * Author: Role 2 - Fault Tolerance Engineer
 * Concepts: #5 Exception handling (invalid input is rejected, the system keeps running)
 * Purpose: Thrown when Postman or the menu names a sensor that does not exist (e.g. "T9").
 */
public class UnknownSensorException extends RuntimeException {

    public UnknownSensorException(String id) {
        super("Unknown sensor '" + id + "' - valid sensors are T1, T2, T3, H1, S1");
    }
}
