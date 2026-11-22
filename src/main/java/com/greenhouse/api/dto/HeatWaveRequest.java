package com.greenhouse.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Author: Role 2 - Fault Tolerance Engineer
 * Concepts: #5 Invalid input is rejected before it can reach the real-time system
 * Purpose: Body of POST /api/environment/heatwave (scenario F5: delta = +14 C).
 */
public record HeatWaveRequest(
        @NotNull(message = "delta is required")
        @DecimalMin(value = "-30", message = "delta must be >= -30 C")
        @DecimalMax(value = "30", message = "delta must be <= 30 C")
        Double delta) {
}
