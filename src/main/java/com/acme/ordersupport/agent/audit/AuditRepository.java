package com.acme.ordersupport.agent.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {

    private static final RowMapper<ToolInvocation> MAPPER = (rs, rowNum) -> new ToolInvocation(
            rs.getLong("id"),
            rs.getObject("invocation_id", UUID.class),
            rs.getObject("conversation_id", UUID.class),
            rs.getObject("customer_id", UUID.class),
            Actor.valueOf(rs.getString("actor")),
            rs.getString("tool_name"),
            rs.getString("arguments"),
            ToolOutcome.valueOf(rs.getString("outcome")),
            rs.getString("detail"),
            rs.getObject("pending_action_id", UUID.class),
            rs.getInt("duration_ms"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private static final String SELECT = """
            SELECT id, invocation_id, conversation_id, customer_id, actor, tool_name, arguments,
                   outcome, detail, pending_action_id, duration_ms, created_at
              FROM tool_invocations
            """;

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(ToolInvocation invocation) {
        return jdbc.sql("""
                        INSERT INTO tool_invocations (invocation_id, conversation_id, customer_id, actor,
                                                      tool_name, arguments, outcome, detail,
                                                      pending_action_id, duration_ms)
                        VALUES (:invocationId, :conversationId, :customerId, :actor, :toolName,
                                CAST(:arguments AS jsonb), :outcome, :detail, :pendingActionId, :durationMs)
                        RETURNING id
                        """)
                .param("invocationId", invocation.invocationId())
                .param("conversationId", invocation.conversationId())
                .param("customerId", invocation.customerId())
                .param("actor", invocation.actor().name())
                .param("toolName", invocation.toolName())
                .param("arguments", invocation.arguments())
                .param("outcome", invocation.outcome().name())
                .param("detail", invocation.detail())
                .param("pendingActionId", invocation.pendingActionId())
                .param("durationMs", invocation.durationMs())
                .query(Long.class)
                .single();
    }

    public List<ToolInvocation> findByConversation(UUID conversationId) {
        return jdbc.sql(SELECT + " WHERE conversation_id = :conversationId ORDER BY id")
                .param("conversationId", conversationId)
                .query(MAPPER)
                .list();
    }

    /**
     * Rows written since a watermark. The chat response reports the tools a turn
     * used by reading them back from here rather than from a list kept alongside,
     * so what the caller is shown and what was durably recorded cannot disagree.
     */
    public List<ToolInvocation> findByConversationSince(UUID conversationId, long afterId) {
        return jdbc.sql(SELECT + " WHERE conversation_id = :conversationId AND id > :afterId ORDER BY id")
                .param("conversationId", conversationId)
                .param("afterId", afterId)
                .query(MAPPER)
                .list();
    }

    public long highWaterMark(UUID conversationId) {
        Long maxId = jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM tool_invocations WHERE conversation_id = :id")
                .param("id", conversationId)
                .query(Long.class)
                .single();
        return maxId != null ? maxId : 0L;
    }

    public List<ToolInvocation> findByCustomer(UUID customerId, int limit) {
        return jdbc.sql(SELECT + " WHERE customer_id = :customerId ORDER BY id DESC LIMIT :limit")
                .param("customerId", customerId)
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }
}
