package com.greenhouse.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Author: Role 5 - Timing & Integration Lead
 * Concepts: #5 Invalid input is rejected before it can reach the real-time system
 * Purpose: Body of POST /api/scenario/start, e.g. {"name":"F1 - Wrong value","expect":"voter MASKED"}.
 *          Used only to print a banner in the CLI so the audience sees which scenario is running.
 */
public record ScenarioRequest(
        @NotBlank(message = "name is required")
        @Size(max = 80, message = "name must be at most 80 characters")
        String name,

        @Size(max = 200, message = "expect must be at most 200 characters")
        String expect) {
}
