package com.acme.ordersupport.order.repo;

import com.acme.ordersupport.order.model.ReturnStatus;
import com.acme.ordersupport.order.model.ReturnView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReturnRepository {

    private static final RowMapper<ReturnView> MAPPER = (rs, rowNum) -> new ReturnView(
            rs.getObject("id", UUID.class),
            rs.getString("rma_number"),
            rs.getObject("order_id", UUID.class),
            rs.getString("order_number"),
            ReturnStatus.valueOf(rs.getString("status")),
            rs.getString("reason"),
            Rows.instant(rs, "requested_at"),
            Rows.instant(rs, "resolved_at"));

    private static final String SELECT = """
            SELECT r.id, r.rma_number, r.order_id, o.order_number, r.status, r.reason,
                   r.requested_at, r.resolved_at
              FROM return_requests r
              JOIN orders o ON o.id = r.order_id
            """;

    private final JdbcClient jdbc;

    public ReturnRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReturnView> findByOrder(UUID orderId) {
        return jdbc.sql(SELECT + " WHERE r.order_id = :orderId ORDER BY r.requested_at DESC")
                .param("orderId", orderId)
                .query(MAPPER)
                .list();
    }

    public Optional<ReturnView> findOpenByOrder(UUID orderId) {
        return findByOrder(orderId).stream().filter(r -> r.status().open()).findFirst();
    }

    public ReturnView insert(UUID id, String rmaNumber, UUID orderId, String orderNumber, UUID orderItemId,
                             ReturnStatus status, String reason, Instant requestedAt) {
        jdbc.sql("""
                        INSERT INTO return_requests (id, rma_number, order_id, order_item_id, status,
                                                     reason, requested_at)
                        VALUES (:id, :rma, :orderId, :orderItemId, :status, :reason, :requestedAt)
                        """)
                .param("id", id)
                .param("rma", rmaNumber)
                .param("orderId", orderId)
                .param("orderItemId", orderItemId)
                .param("status", status.name())
                .param("reason", reason)
                .param("requestedAt", Rows.utc(requestedAt))
                .update();
        return new ReturnView(id, rmaNumber, orderId, orderNumber, status, reason, requestedAt, null);
    }
}
