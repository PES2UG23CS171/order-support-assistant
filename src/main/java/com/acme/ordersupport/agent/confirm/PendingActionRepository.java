package com.acme.ordersupport.agent.confirm;

import com.acme.ordersupport.order.model.ActionType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PendingActionRepository {

    private static final RowMapper<PendingAction> MAPPER = (rs, rowNum) -> new PendingAction(
            rs.getObject("id", UUID.class),
            rs.getObject("conversation_id", UUID.class),
            rs.getObject("customer_id", UUID.class),
            ActionType.valueOf(rs.getString("action_type")),
            rs.getString("tool_name"),
            rs.getObject("order_id", UUID.class),
            rs.getString("order_number"),
            rs.getString("arguments"),
            rs.getString("summary"),
            PendingActionStatus.valueOf(rs.getString("status")),
            instant(rs.getObject("created_at", OffsetDateTime.class)),
            instant(rs.getObject("expires_at", OffsetDateTime.class)),
            instant(rs.getObject("decided_at", OffsetDateTime.class)),
            rs.getString("result"));

    private static final String SELECT = """
            SELECT p.id, p.conversation_id, p.customer_id, p.action_type, p.tool_name, p.order_id,
                   o.order_number, p.arguments, p.summary, p.status, p.created_at, p.expires_at,
                   p.decided_at, p.result
              FROM pending_actions p
              JOIN orders o ON o.id = p.order_id
            """;

    private final JdbcClient jdbc;

    public PendingActionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PendingAction action) {
        jdbc.sql("""
                        INSERT INTO pending_actions (id, conversation_id, customer_id, action_type, tool_name,
                                                     order_id, arguments, summary, status, created_at, expires_at)
                        VALUES (:id, :conversationId, :customerId, :actionType, :toolName, :orderId,
                                CAST(:arguments AS jsonb), :summary, :status, :createdAt, :expiresAt)
                        """)
                .param("id", action.id())
                .param("conversationId", action.conversationId())
                .param("customerId", action.customerId())
                .param("actionType", action.actionType().name())
                .param("toolName", action.toolName())
                .param("orderId", action.orderId())
                .param("arguments", action.argumentsJson())
                .param("summary", action.summary())
                .param("status", action.status().name())
                .param("createdAt", utc(action.createdAt()))
                .param("expiresAt", utc(action.expiresAt()))
                .update();
    }

    public Optional<PendingAction> findById(UUID id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).query(MAPPER).optional();
    }

    public List<PendingAction> findByConversation(UUID conversationId) {
        return jdbc.sql(SELECT + " WHERE p.conversation_id = :id ORDER BY p.created_at")
                .param("id", conversationId)
                .query(MAPPER)
                .list();
    }

    /**
     * Compare-and-set from one status to another.
     *
     * <p>Returns 1 only for the caller that won. This is what makes a confirmation
     * token single-use: two confirmations arriving together both try to claim
     * {@code AWAITING_CONFIRMATION -> EXECUTING} and exactly one of them can.
     */
    public int transition(UUID id, PendingActionStatus from, PendingActionStatus to, Instant decidedAt) {
        return jdbc.sql("""
                        UPDATE pending_actions
                           SET status = :to,
                               decided_at = COALESCE(:decidedAt, decided_at)
                         WHERE id = :id AND status = :from
                        """)
                .param("to", to.name())
                .param("from", from.name())
                .param("decidedAt", utc(decidedAt))
                .param("id", id)
                .update();
    }

    public void recordResult(UUID id, String resultJson) {
        jdbc.sql("UPDATE pending_actions SET result = CAST(:result AS jsonb) WHERE id = :id")
                .param("result", resultJson)
                .param("id", id)
                .update();
    }

    private static Instant instant(OffsetDateTime value) {
        return value != null ? value.toInstant() : null;
    }

    private static OffsetDateTime utc(Instant value) {
        return value != null ? OffsetDateTime.ofInstant(value, ZoneOffset.UTC) : null;
    }
}
