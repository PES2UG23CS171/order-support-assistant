package com.acme.ordersupport.testsupport;

import com.acme.ordersupport.order.model.OrderLineView;
import com.acme.ordersupport.order.model.OrderStatus;
import com.acme.ordersupport.order.model.OrderView;
import com.acme.ordersupport.order.model.ShipmentStatus;
import com.acme.ordersupport.order.model.ShipmentView;
import com.acme.ordersupport.order.repo.OrderRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Fixture builders, so a test reads as the case it is about and not as setup. */
public final class Fixtures {

    public static final UUID ADA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID BLAKE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final String ADA_KEY = "osa_demo_ada_key";
    public static final String BLAKE_KEY = "osa_demo_blake_key";

    /** Every unit test that cares about elapsed time is anchored here. */
    public static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private Fixtures() {
    }

    public static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    public static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    public static OrderRow orderRow(String orderNumber, UUID customerId, OrderStatus status) {
        return new OrderRow(UUID.nameUUIDFromBytes(orderNumber.getBytes()), orderNumber, customerId, status,
                "USD", new BigDecimal("249.00"), NOW.minusSeconds(86_400), null, "Austin", "US", 0);
    }

    public static OrderRow orderRow(String orderNumber, UUID customerId, OrderStatus status,
                                    BigDecimal total, int version) {
        return new OrderRow(UUID.nameUUIDFromBytes(orderNumber.getBytes()), orderNumber, customerId, status,
                "USD", total, NOW.minusSeconds(86_400), null, "Austin", "US", version);
    }

    public static OrderLineView line(String sku, String category, boolean returnable, String unitPrice) {
        return new OrderLineView(UUID.nameUUIDFromBytes(sku.getBytes()), sku, sku + " description",
                category, 1, new BigDecimal(unitPrice), returnable);
    }

    public static ShipmentView deliveredDaysAgo(int days) {
        return new ShipmentView("UPS", "1Z999AA10123456784", ShipmentStatus.DELIVERED,
                NOW.minusSeconds((days + 3L) * 86_400), LocalDate.ofInstant(NOW, ZoneOffset.UTC).minusDays(days),
                NOW.minusSeconds(days * 86_400L), "Austin, TX");
    }

    public static ShipmentView inTransit() {
        return new ShipmentView("UPS", "1Z999AA10123456784", ShipmentStatus.IN_TRANSIT,
                NOW.minusSeconds(172_800), LocalDate.ofInstant(NOW, ZoneOffset.UTC).plusDays(1), null,
                "Dallas, TX");
    }

    public static OrderView orderView(String orderNumber, OrderStatus status, List<OrderLineView> lines,
                                      ShipmentView shipment) {
        return new OrderView(UUID.nameUUIDFromBytes(orderNumber.getBytes()), orderNumber, status, "USD",
                lines.stream().map(OrderLineView::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add),
                NOW.minusSeconds(864_000), null, "Austin", "US", 0, lines, shipment);
    }
}
