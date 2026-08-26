package com.acme.ordersupport.guardrail;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class GuardrailEventRepository {

    private final JdbcClient jdbc;

    public GuardrailEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Same reasoning as the tool audit: a blocked prompt must leave a trace regardless. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID conversationId, UUID customerId, GuardDecision decision, String excerpt) {
        jdbc.sql("""
                        INSERT INTO guardrail_events (conversation_id, customer_id, guardrail, verdict,
                                                      reason, excerpt)
                        VALUES (:conversationId, :customerId, :guardrail, 'BLOCKED', :reason, :excerpt)
                        """)
                .param("conversationId", conversationId)
                .param("customerId", customerId)
                .param("guardrail", decision.guardrail())
                .param("reason", decision.reason())
                .param("excerpt", excerpt == null ? null : excerpt.substring(0, Math.min(excerpt.length(), 500)))
                .update();
    }

    public List<GuardrailEvent> findByConversation(UUID conversationId) {
        return jdbc.sql("""
                        SELECT id, conversation_id, customer_id, guardrail, verdict, reason, excerpt, created_at
                          FROM guardrail_events
                         WHERE conversation_id = :conversationId
                         ORDER BY id
                        """)
                .param("conversationId", conversationId)
                .query((rs, rowNum) -> new GuardrailEvent(
                        rs.getLong("id"),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("guardrail"),
                        rs.getString("verdict"),
                        rs.getString("reason"),
                        rs.getString("excerpt"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
