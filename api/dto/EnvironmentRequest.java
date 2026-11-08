package com.greenhouse.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Author: Role 2 - Fault Tolerance Engineer
 * Concepts: #5 Invalid input is rejected before it can reach the real-time system
 * Purpose: Body of POST /api/environment - the simulated greenhouse values. All fields are required
 *          and must be physically possible.
 */
public record EnvironmentRequest(
        @NotNull(message = "temperature is required")
        @DecimalMin(value = "-40", message = "temperature must be >= -40 C")
        @DecimalMax(value = "80", message = "temperature must be <= 80 C")
        Double temperature,

        @NotNull(message = "humidity is required")
        @DecimalMin(value = "0", message = "humidity must be >= 0 %")
        @DecimalMax(value = "100", message = "humidity must be <= 100 %")
        Double humidity,

        @NotNull(message = "soil is required")
        @DecimalMin(value = "0", message = "soil must be >= 0 %")
        @DecimalMax(value = "100", message = "soil must be <= 100 %")
        Double soil) {
}
