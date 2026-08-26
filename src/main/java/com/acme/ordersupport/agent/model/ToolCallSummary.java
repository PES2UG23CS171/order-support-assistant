package com.acme.ordersupport.agent.model;

import java.time.Instant;

public record ToolCallSummary(String tool, String actor, String outcome, String detail,
                              int durationMs, Instant at) {
}
