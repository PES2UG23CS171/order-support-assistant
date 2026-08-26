package com.acme.ordersupport.order.repo;

import com.acme.ordersupport.order.model.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The raw orders row, including the owning customer. It stays inside the
 * persistence and service layers; callers receive an
 * {@link com.acme.ordersupport.order.model.OrderView} instead.
 */
public record OrderRow(UUID id, String orderNumber, UUID customerId, OrderStatus status,
                       String currency, BigDecimal totalAmount, Instant placedAt, Instant cancelledAt,
                       String shippingCity, String shippingCountry, int version) {
}
