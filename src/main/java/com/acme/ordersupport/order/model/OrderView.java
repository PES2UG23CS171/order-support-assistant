package com.acme.ordersupport.order.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The read model handed to callers -- REST clients and the assistant alike.
 *
 * <p>It deliberately carries no customer id. Nothing that identifies the account
 * needs to reach the model's context to answer a question about an order, so
 * nothing does.
 */
public record OrderView(UUID id, String orderNumber, OrderStatus status, String currency,
                        BigDecimal totalAmount, Instant placedAt, Instant cancelledAt,
                        String shippingCity, String shippingCountry, int version,
                        List<OrderLineView> lines, ShipmentView shipment) {

    public Optional<ShipmentView> shipmentOptional() {
        return Optional.ofNullable(shipment);
    }

    public List<OrderLineView> returnableLines() {
        return lines.stream().filter(OrderLineView::returnable).toList();
    }
}
