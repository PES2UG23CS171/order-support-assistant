package com.acme.ordersupport.order.model;

import java.time.Instant;
import java.time.LocalDate;

public record ShipmentView(String carrier, String trackingNumber, ShipmentStatus status,
                           Instant shippedAt, LocalDate estimatedDelivery, Instant deliveredAt,
                           String lastLocation) {
}
