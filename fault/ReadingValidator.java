package com.greenhouse.fault;

import com.greenhouse.sim.Reading;

/**
 * Author: Role 2 - Fault Tolerance Engineer
 * Concepts: #2 Fault -> error detection (an impossible value is an ERROR caused by a sensor FAULT)
 * Purpose: Plausibility check of every reading before it is used. Impossible values are rejected
 *          so they can never reach the voter or the controller.
 */
public class ReadingValidator {

    public static final double MIN_TEMPERATURE = -40.0;
    public static final double MAX_TEMPERATURE = 80.0;

    /** @return null if the reading is plausible, otherwise the reason why it is rejected */
    public String validate(Reading r) {
        double v = r.value();
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "not a number";
        }
        return switch (r.type()) {
            case TEMPERATURE -> (v < MIN_TEMPERATURE || v > MAX_TEMPERATURE)
                    ? "outside physical range -40..80 C" : null;
            case HUMIDITY, SOIL -> (v < 0 || v > 100) ? "outside 0..100 %" : null;
        };
    }
}
