package com.acme.ordersupport.order.repo;

import com.acme.ordersupport.order.model.RefundStatus;
import com.acme.ordersupport.order.model.RefundView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RefundRepository {

    private static final RowMapper<RefundView> MAPPER = (rs, rowNum) -> new RefundView(
            rs.getObject("id", UUID.class),
            rs.getObject("order_id", UUID.class),
            rs.getString("order_number"),
            RefundStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getString("method"),
            Rows.instant(rs, "initiated_at"),
            Rows.date(rs, "expected_settlement_date"),
            Rows.instant(rs, "settled_at"),
            rs.getString("external_reference"));

    private final JdbcClient jdbc;

    public RefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<RefundView> findByOrder(UUID orderId) {
        return jdbc.sql("""
                        SELECT f.id, f.order_id, o.order_number, f.status, f.amount, f.currency, f.method,
                               f.initiated_at, f.expected_settlement_date, f.settled_at, f.external_reference
                          FROM refunds f
                          JOIN orders o ON o.id = f.order_id
                         WHERE f.order_id = :orderId
                         ORDER BY f.initiated_at DESC
                        """)
                .param("orderId", orderId)
                .query(MAPPER)
                .list();
    }

    public RefundView insert(UUID id, UUID orderId, String orderNumber, UUID returnRequestId,
                             RefundStatus status, BigDecimal amount, String currency, String method,
                             Instant initiatedAt, LocalDate expectedSettlementDate, String externalReference) {
        jdbc.sql("""
                        INSERT INTO refunds (id, order_id, return_request_id, status, amount, currency,
                                             method, initiated_at, expected_settlement_date, external_reference)
                        VALUES (:id, :orderId, :returnRequestId, :status, :amount, :currency,
                                :method, :initiatedAt, :expectedSettlementDate, :externalReference)
                        """)
                .param("id", id)
                .param("orderId", orderId)
                .param("returnRequestId", returnRequestId)
                .param("status", status.name())
                .param("amount", amount)
                .param("currency", currency)
                .param("method", method)
                .param("initiatedAt", Rows.utc(initiatedAt))
                .param("expectedSettlementDate", expectedSettlementDate)
                .param("externalReference", externalReference)
                .update();
        return new RefundView(id, orderId, orderNumber, status, amount, currency, method,
                initiatedAt, expectedSettlementDate, null, externalReference);
    }
}
