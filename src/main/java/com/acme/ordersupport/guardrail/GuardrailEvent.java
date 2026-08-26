package com.acme.ordersupport.guardrail;

import java.time.Instant;
import java.util.UUID;

public record GuardrailEvent(long id, UUID conversationId, UUID customerId, String guardrail,
                             String verdict, String reason, String excerpt, Instant createdAt) {
}
