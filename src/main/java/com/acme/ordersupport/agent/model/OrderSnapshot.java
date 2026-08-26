package com.acme.ordersupport.agent.model;

import com.acme.ordersupport.order.model.OrderView;
import java.math.BigDecimal;
import java.util.List;

/**
 * What the model is shown about an order.
 *
 * <p>It carries the eligibility verdicts rather than the raw fields needed to
 * derive them. The model does not compute whether the return window has closed --
 * the service already did, in the same code that would refuse the action -- so it
 * cannot arrive at a different answer and promise a return the backend will reject.
 */
public record OrderSnapshot(String orderNumber, String status, String currency, BigDecimal total,
                            String placedAt, List<Item> items, Delivery delivery,
                            boolean cancellable, String cancellationNote,
                            boolean returnable, String returnNote) implements ToolResult {

    public record Item(String sku, String description, String category, int quantity,
                       BigDecimal unitPrice, boolean returnable) {
    }

    public record Delivery(String status, String carrier, String trackingNumber,
                           String estimatedDelivery, String deliveredAt, String lastLocation) {
    }

    public static OrderSnapshot from(OrderView order, boolean cancellable, String cancellationNote,
                                     boolean returnable, String returnNote) {
        List<Item> items = order.lines().stream()
                .map(line -> new Item(line.sku(), line.description(), line.category(), line.quantity(),
                        line.unitPrice(), line.returnable()))
                .toList();
        Delivery delivery = order.shipment() == null ? null : new Delivery(
                order.shipment().status().name(),
                order.shipment().carrier(),
                order.shipment().trackingNumber(),
                Text.iso(order.shipment().estimatedDelivery()),
                Text.iso(order.shipment().deliveredAt()),
                order.shipment().lastLocation());
        return new OrderSnapshot(order.orderNumber(), order.status().name(), order.currency(),
                order.totalAmount(), Text.iso(order.placedAt()), items, delivery,
                cancellable, cancellationNote, returnable, returnNote);
    }
}
