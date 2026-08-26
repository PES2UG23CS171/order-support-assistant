package com.acme.ordersupport.agent.model;

import com.acme.ordersupport.order.model.OrderView;
import com.acme.ordersupport.order.model.ShipmentView;

public record DeliverySnapshot(String orderNumber, String orderStatus, String shipmentStatus,
                               String carrier, String trackingNumber, String estimatedDelivery,
                               String deliveredAt, String lastLocation, String note)
        implements ToolResult {

    public static DeliverySnapshot from(OrderView order) {
        ShipmentView shipment = order.shipment();
        if (shipment == null) {
            return new DeliverySnapshot(order.orderNumber(), order.status().name(), null, null, null,
                    null, null, null,
                    "No shipment has been created for this order yet; it is still being prepared.");
        }
        return new DeliverySnapshot(order.orderNumber(), order.status().name(), shipment.status().name(),
                shipment.carrier(), shipment.trackingNumber(), Text.iso(shipment.estimatedDelivery()),
                Text.iso(shipment.deliveredAt()), shipment.lastLocation(), null);
    }
}
