package com.acme.ordersupport.agent;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {

    public record StoredMessage(String role, String content) {
        public static final String USER = "USER";
        public static final String ASSISTANT = "ASSISTANT";
    }

    private final JdbcClient jdbc;

    public ConversationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID create(UUID customerId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO conversations (id, customer_id) VALUES (:id, :customerId)")
                .param("id", id)
                .param("customerId", customerId)
                .update();
        return id;
    }

    public Optional<UUID> ownerOf(UUID conversationId) {
        return jdbc.sql("SELECT customer_id FROM conversations WHERE id = :id")
                .param("id", conversationId)
                .query((rs, rowNum) -> rs.getObject("customer_id", UUID.class))
                .optional();
    }

    public void append(UUID conversationId, String role, String content) {
        jdbc.sql("""
                        INSERT INTO chat_messages (conversation_id, role, content)
                        VALUES (:conversationId, :role, :content)
                        """)
                .param("conversationId", conversationId)
                .param("role", role)
                .param("content", content)
                .update();
        jdbc.sql("UPDATE conversations SET last_active_at = now() WHERE id = :id")
                .param("id", conversationId)
                .update();
    }

    /** The most recent {@code limit} messages, oldest first. */
    public List<StoredMessage> recentMessages(UUID conversationId, int limit) {
        List<StoredMessage> newestFirst = jdbc.sql("""
                        SELECT role, content FROM chat_messages
                         WHERE conversation_id = :conversationId
                         ORDER BY id DESC
                         LIMIT :limit
                        """)
                .param("conversationId", conversationId)
                .param("limit", limit)
                .query((rs, rowNum) -> new StoredMessage(rs.getString("role"), rs.getString("content")))
                .list();
        return newestFirst.reversed();
    }

    public Optional<OffsetDateTime> lastActiveAt(UUID conversationId) {
        return jdbc.sql("SELECT last_active_at FROM conversations WHERE id = :id")
                .param("id", conversationId)
                .query((rs, rowNum) -> rs.getObject("last_active_at", OffsetDateTime.class))
                .optional();
    }
}
