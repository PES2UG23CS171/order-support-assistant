package com.acme.ordersupport.agent.audit;

import java.time.Instant;
import java.util.UUID;

public record ToolInvocation(long id, UUID invocationId, UUID conversationId, UUID customerId,
                             Actor actor, String toolName, String arguments, ToolOutcome outcome,
                             String detail, UUID pendingActionId, int durationMs, Instant createdAt) {
}
