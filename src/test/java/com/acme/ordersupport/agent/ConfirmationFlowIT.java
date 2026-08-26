package com.acme.ordersupport.agent;

import static com.acme.ordersupport.testsupport.Fixtures.ADA;
import static com.acme.ordersupport.testsupport.Fixtures.BLAKE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.ordersupport.agent.audit.ToolOutcome;
import com.acme.ordersupport.agent.confirm.ConfirmationDecision;
import com.acme.ordersupport.agent.confirm.ConfirmationErrors;
import com.acme.ordersupport.agent.confirm.ConfirmationOutcome;
import com.acme.ordersupport.agent.confirm.ConfirmationService;
import com.acme.ordersupport.agent.confirm.PendingAction;
import com.acme.ordersupport.agent.confirm.PendingActionStatus;
import com.acme.ordersupport.order.OrderService;
import com.acme.ordersupport.order.model.ActionType;
import com.acme.ordersupport.testsupport.DatabaseFixtures;
import com.acme.ordersupport.testsupport.IntegrationTest;
import com.acme.ordersupport.testsupport.PostgresSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The gate, against real rows: proposing changes nothing, approving changes exactly one thing. */
@IntegrationTest
class ConfirmationFlowIT extends PostgresSupport {

    @Autowired
    private ConfirmationService confirmations;

    @Autowired
    private ConversationRepository conversations;

    @Autowired
    private OrderService orderService;

    @Autowired
    private com.acme.ordersupport.agent.audit.AuditService audit;

    @Autowired
    private DatabaseFixtures fixtures;

    private UUID conversationId;

    @BeforeEach
    void reset() {
        fixtures.reset();
        conversationId = conversations.create(ADA);
    }

    private PendingAction proposeCancellation() {
        UUID orderId = orderService.lookup(ADA, "ORD-1001").id();
        return confirmations.propose(conversationId, ADA, ActionType.CANCEL_ORDER, "cancelOrder", orderId,
                "ORD-1001", Map.of("orderNumber", "ORD-1001", "reason", "ordered by mistake"),
                "Cancel order ORD-1001");
    }

    @Test
    void aProposalLeavesTheOrderExactlyAsItWas() {
        proposeCancellation();

        assertThat(fixtures.statusOf("ORD-1001")).isEqualTo("PROCESSING");
        assertThat(fixtures.countRefundsFor("ORD-1001")).isZero();
    }

    @Test
    void approvingExecutesTheActionAndRecordsItsResult() {
        PendingAction proposal = proposeCancellation();

        ConfirmationOutcome outcome = confirmations.decide(ADA, proposal.id(), ConfirmationDecision.APPROVE);

        assertThat(outcome.status()).isEqualTo(PendingActionStatus.EXECUTED);
        assertThat(fixtures.statusOf("ORD-1001")).isEqualTo("CANCELLED");
        assertThat(fixtures.countRefundsFor("ORD-1001")).isEqualTo(1);
        assertThat(confirmations.forConversation(conversationId)).singleElement()
                .satisfies(action -> {
                    assertThat(action.status()).isEqualTo(PendingActionStatus.EXECUTED);
                    assertThat(action.resultJson()).contains("CANCELLED");
                    assertThat(action.decidedAt()).isNotNull();
                });
    }

    @Test
    void rejectingClosesTheProposalAndLeavesTheOrderAlone() {
        PendingAction proposal = proposeCancellation();

        ConfirmationOutcome outcome = confirmations.decide(ADA, proposal.id(), ConfirmationDecision.REJECT);

        assertThat(outcome.status()).isEqualTo(PendingActionStatus.REJECTED);
        assertThat(fixtures.statusOf("ORD-1001")).isEqualTo("PROCESSING");
    }

    @Test
    void aTokenCannotBeRedeemedTwice() {
        PendingAction proposal = proposeCancellation();
        confirmations.decide(ADA, proposal.id(), ConfirmationDecision.APPROVE);

        assertThatThrownBy(() -> confirmations.decide(ADA, proposal.id(), ConfirmationDecision.APPROVE))
                .isInstanceOf(ConfirmationErrors.AlreadyDecided.class);
    }

    @Test
    void anotherCustomerCannotRedeemTheToken() {
        PendingAction proposal = proposeCancellation();

        assertThatThrownBy(() -> confirmations.decide(BLAKE, proposal.id(), ConfirmationDecision.APPROVE))
                .isInstanceOf(ConfirmationErrors.NotFound.class);

        assertThat(fixtures.statusOf("ORD-1001")).isEqualTo("PROCESSING");
        assertThat(audit.forConversation(conversationId))
                .anySatisfy(invocation -> {
                    assertThat(invocation.outcome()).isEqualTo(ToolOutcome.NOT_FOUND);
                    assertThat(invocation.detail()).contains("different customer");
                });
    }

    @Test
    void concurrentConfirmationsOfTheSameTokenExecuteOnce() throws Exception {
        // The double-clicked Confirm button. Only one request may claim the row.
        PendingAction proposal = proposeCancellation();
        int attempts = 6;
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        List<Future<String>> results = new ArrayList<>();

        for (int i = 0; i < attempts; i++) {
            results.add(pool.submit(() -> {
                startLine.await();
                try {
                    confirmations.decide(ADA, proposal.id(), ConfirmationDecision.APPROVE);
                    return "executed";
                }
                catch (RuntimeException ex) {
                    return "rejected";
                }
            }));
        }
        startLine.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        List<String> outcomes = new ArrayList<>();
        for (Future<String> result : results) {
            outcomes.add(result.get());
        }

        assertThat(outcomes).filteredOn("executed"::equals).hasSize(1);
        assertThat(fixtures.countRefundsFor("ORD-1001")).isEqualTo(1);
    }

    @Test
    void aProposalThatIsNoLongerLegalWhenApprovedFailsWithoutTouchingTheOrder() {
        PendingAction proposal = proposeCancellation();
        // The order ships while the customer is reading the confirmation.
        orderService.cancel(ADA, "ORD-1001", "cancelled through the portal instead");

        assertThatThrownBy(() -> confirmations.decide(ADA, proposal.id(), ConfirmationDecision.APPROVE))
                .isInstanceOf(RuntimeException.class);

        assertThat(confirmations.forConversation(conversationId)).singleElement()
                .satisfies(action -> assertThat(action.status()).isEqualTo(PendingActionStatus.FAILED));
        assertThat(fixtures.countRefundsFor("ORD-1001")).isEqualTo(1);
    }
}
