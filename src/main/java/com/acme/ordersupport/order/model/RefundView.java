package com.acme.ordersupport.order.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RefundView(UUID id, UUID orderId, String orderNumber, RefundStatus status,
                         BigDecimal amount, String currency, String method, Instant initiatedAt,
                         LocalDate expectedSettlementDate, Instant settledAt, String externalReference) {
}
