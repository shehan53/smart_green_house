package com.greenhouse.api.dto;

import com.greenhouse.sim.FaultMode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Author: Role 2 - Fault Tolerance Engineer
 * Concepts: #2 Fault injection on command, #5 invalid input rejected
 * Purpose: Body of POST /api/sensors/{id}/fault, e.g. {"type":"WRONG_VALUE","value":15} or {"type":"DEAD"}.
 */
public record FaultRequest(
        @NotNull(message = "type is required (NONE, WRONG_VALUE, STUCK, DEAD)")
        FaultMode type,

        @DecimalMin(value = "-100", message = "value must be >= -100")
        @DecimalMax(value = "100", message = "value must be <= 100")
        Double value) {
}
