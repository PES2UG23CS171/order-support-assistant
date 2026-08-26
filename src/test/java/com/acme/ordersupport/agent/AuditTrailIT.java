package com.acme.ordersupport.agent;

import static com.acme.ordersupport.testsupport.Fixtures.ADA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ordersupport.agent.audit.Actor;
import com.acme.ordersupport.agent.audit.AuditService;
import com.acme.ordersupport.agent.audit.ToolOutcome;
import com.acme.ordersupport.order.OrderService;
import com.acme.ordersupport.order.error.ActionNotAllowedException;
import com.acme.ordersupport.testsupport.DatabaseFixtures;
import com.acme.ordersupport.testsupport.IntegrationTest;
import com.acme.ordersupport.testsupport.PostgresSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit log's one hard requirement: it records what happened even when what
 * happened was rolled back. The gaps in an audit trail are exactly where the
 * interesting failures are, so this gets its own test.
 */
@IntegrationTest
class AuditTrailIT extends PostgresSupport {

    @Autowired
    private AuditService audit;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ConversationRepository conversations;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseFixtures fixtures;

    private UUID conversationId;

    @BeforeEach
    void reset() {
        fixtures.reset();
        conversationId = conversations.create(ADA);
    }

    @Test
    void anAuditRowSurvivesTheRollbackOfTheTransactionItRecorded() {
        var template = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> template.executeWithoutResult(status -> audit.around(
                "cancelOrder", Actor.LLM_TOOL_CALL, new SupportSession(conversationId, ADA),
                Map.of("orderNumber", "ORD-1002"),
                () -> orderService.cancel(ADA, "ORD-1002", "already shipped"))))
                .isInstanceOf(ActionNotAllowedException.class);

        // The cancellation was refused and its transaction discarded; the attempt
        // is still on the record.
        assertThat(audit.forConversation(conversationId)).singleElement().satisfies(invocation -> {
            assertThat(invocation.toolName()).isEqualTo("cancelOrder");
            assertThat(invocation.outcome()).isEqualTo(ToolOutcome.NOT_ALLOWED);
            assertThat(invocation.detail()).contains("ORDER_ALREADY_SHIPPED");
        });
        assertThat(fixtures.statusOf("ORD-1002")).isEqualTo("SHIPPED");
    }

    @Test
    void successfulCallsAreRecordedWithTheirDurationAndArguments() {
        audit.around("lookupOrder", Actor.LLM_TOOL_CALL, new SupportSession(conversationId, ADA),
                Map.of("orderNumber", "ORD-1001"), () -> orderService.lookup(ADA, "ORD-1001"));

        assertThat(audit.forConversation(conversationId)).singleElement().satisfies(invocation -> {
            assertThat(invocation.outcome()).isEqualTo(ToolOutcome.SUCCESS);
            assertThat(invocation.actor()).isEqualTo(Actor.LLM_TOOL_CALL);
            assertThat(invocation.arguments()).contains("ORD-1001");
            assertThat(invocation.durationMs()).isGreaterThanOrEqualTo(0);
            assertThat(invocation.invocationId()).isNotNull();
        });
    }

    @Test
    void theWatermarkIsolatesOneTurnsToolCallsFromTheConversationsHistory() {
        audit.around("lookupOrder", Actor.LLM_TOOL_CALL, new SupportSession(conversationId, ADA),
                Map.of("orderNumber", "ORD-1001"), () -> orderService.lookup(ADA, "ORD-1001"));
        long watermark = audit.highWaterMark(conversationId);

        audit.around("trackDelivery", Actor.LLM_TOOL_CALL, new SupportSession(conversationId, ADA),
                Map.of("orderNumber", "ORD-1002"), () -> orderService.lookup(ADA, "ORD-1002"));

        assertThat(audit.since(conversationId, watermark)).singleElement()
                .satisfies(invocation -> assertThat(invocation.toolName()).isEqualTo("trackDelivery"));
        assertThat(audit.forConversation(conversationId)).hasSize(2);
    }

    @Test
    void restCallsShareTheAuditTableAndAreDistinguishedOnlyByActor() {
        audit.around("cancelOrder", Actor.REST_CLIENT, new SupportSession(null, ADA),
                Map.of("orderNumber", "ORD-1001"), () -> orderService.lookup(ADA, "ORD-1001"));

        assertThat(audit.recentForCustomer(ADA, 10)).singleElement()
                .satisfies(invocation -> {
                    assertThat(invocation.actor()).isEqualTo(Actor.REST_CLIENT);
                    assertThat(invocation.conversationId()).isNull();
                });
    }
}
