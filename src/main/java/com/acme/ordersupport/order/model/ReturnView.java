package com.acme.ordersupport.order.model;

import java.time.Instant;
import java.util.UUID;

public record ReturnView(UUID id, String rmaNumber, UUID orderId, String orderNumber,
                         ReturnStatus status, String reason, Instant requestedAt, Instant resolvedAt) {
}
