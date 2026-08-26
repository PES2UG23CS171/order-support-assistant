package com.acme.ordersupport.order.repo;

import com.acme.ordersupport.order.model.OrderLineView;
import com.acme.ordersupport.order.model.OrderStatus;
import com.acme.ordersupport.order.model.ShipmentStatus;
import com.acme.ordersupport.order.model.ShipmentView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private static final String ORDER_COLUMNS = """
            SELECT id, order_number, customer_id, status, currency, total_amount, placed_at,
                   cancelled_at, shipping_city, shipping_country, version
              FROM orders
            """;

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, rowNum) -> new OrderRow(
            rs.getObject("id", UUID.class),
            rs.getString("order_number"),
            rs.getObject("customer_id", UUID.class),
            OrderStatus.valueOf(rs.getString("status")),
            rs.getString("currency"),
            rs.getBigDecimal("total_amount"),
            Rows.instant(rs, "placed_at"),
            Rows.instant(rs, "cancelled_at"),
            rs.getString("shipping_city"),
            rs.getString("shipping_country"),
            rs.getInt("version"));

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OrderRow> findByOrderNumber(String orderNumber) {
        return jdbc.sql(ORDER_COLUMNS + " WHERE order_number = :orderNumber")
                .param("orderNumber", orderNumber)
                .query(ORDER_MAPPER)
                .optional();
    }

    public Optional<OrderRow> findById(UUID id) {
        return jdbc.sql(ORDER_COLUMNS + " WHERE id = :id").param("id", id).query(ORDER_MAPPER).optional();
    }

    public List<OrderRow> findAllByCustomer(UUID customerId) {
        return jdbc.sql(ORDER_COLUMNS + " WHERE customer_id = :customerId ORDER BY placed_at DESC")
                .param("customerId", customerId)
                .query(ORDER_MAPPER)
                .list();
    }

    public List<OrderLineView> findLines(UUID orderId) {
        return jdbc.sql("""
                        SELECT id, sku, description, category, quantity, unit_price, returnable
                          FROM order_items
                         WHERE order_id = :orderId
                         ORDER BY sku
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new OrderLineView(
                        rs.getObject("id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("description"),
                        rs.getString("category"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBoolean("returnable")))
                .list();
    }

    public Optional<ShipmentView> findShipment(UUID orderId) {
        return jdbc.sql("""
                        SELECT carrier, tracking_number, status, shipped_at, estimated_delivery,
                               delivered_at, last_location
                          FROM shipments
                         WHERE order_id = :orderId
                         ORDER BY shipped_at DESC NULLS LAST
                         LIMIT 1
                        """)
                .param("orderId", orderId)
                .query((rs, rowNum) -> new ShipmentView(
                        rs.getString("carrier"),
                        rs.getString("tracking_number"),
                        ShipmentStatus.valueOf(rs.getString("status")),
                        Rows.instant(rs, "shipped_at"),
                        Rows.date(rs, "estimated_delivery"),
                        Rows.instant(rs, "delivered_at"),
                        rs.getString("last_location")))
                .optional();
    }

    /**
     * Compare-and-set on {@code version}. Returns the number of rows updated, so
     * callers can tell "someone else got there first" apart from "it worked".
     */
    public int updateStatus(UUID orderId, OrderStatus newStatus, int expectedVersion, Instant cancelledAt) {
        return jdbc.sql("""
                        UPDATE orders
                           SET status = :status,
                               cancelled_at = COALESCE(:cancelledAt, cancelled_at),
                               version = version + 1
                         WHERE id = :id AND version = :expectedVersion
                        """)
                .param("status", newStatus.name())
                .param("cancelledAt", Rows.utc(cancelledAt))
                .param("id", orderId)
                .param("expectedVersion", expectedVersion)
                .update();
    }
}
