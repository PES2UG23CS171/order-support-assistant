package com.acme.ordersupport.agent;

import static com.acme.ordersupport.testsupport.Fixtures.ADA;
import static com.acme.ordersupport.testsupport.Fixtures.BLAKE;
import static com.acme.ordersupport.testsupport.Fixtures.NOW;
import static com.acme.ordersupport.testsupport.Fixtures.clockAt;
import static com.acme.ordersupport.testsupport.Fixtures.fixedClock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.ordersupport.agent.audit.AuditWriter;
import com.acme.ordersupport.agent.audit.ToolInvocation;
import com.acme.ordersupport.agent.audit.ToolOutcome;
import com.acme.ordersupport.agent.confirm.ActionExecutor;
import com.acme.ordersupport.agent.confirm.ActionOutcome;
import com.acme.ordersupport.agent.confirm.ConfirmationDecision;
import com.acme.ordersupport.agent.confirm.ConfirmationErrors;
import com.acme.ordersupport.agent.confirm.ConfirmationOutcome;
import com.acme.ordersupport.agent.confirm.ConfirmationService;
import com.acme.ordersupport.agent.confirm.PendingAction;
import com.acme.ordersupport.agent.confirm.PendingActionRepository;
import com.acme.ordersupport.agent.confirm.PendingActionStatus;
import com.acme.ordersupport.config.AppProperties;
import com.acme.ordersupport.order.error.ActionNotAllowedException;
import com.acme.ordersupport.order.model.ActionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The confirmation gate is the guardrail, so its failure modes get their own tests:
 * wrong customer, expired, replayed, and an action that stops being legal between
 * being proposed and being approved.
 */
@ExtendWith(MockitoExtension.class)
class ConfirmationServiceTest {

    private static final UUID TOKEN = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID CONVERSATION = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();

    @Mock
    private PendingActionRepository repository;

    @Mock
    private ActionExecutor executor;

    @Mock
    private AuditWriter auditWriter;

    private ConfirmationService service(Clock clock) {
        return new ConfirmationService(repository, executor, auditWriter, new ObjectMapper(),
                new AppProperties(null, null, new AppProperties.Agent(Duration.ofMinutes(10), 12), null),
                clock);
    }

    private static PendingAction awaiting(UUID customerId) {
        return new PendingAction(TOKEN, CONVERSATION, customerId, ActionType.CANCEL_ORDER, "cancelOrder",
                ORDER_ID, "ORD-1001", "{\"orderNumber\":\"ORD-1001\",\"reason\":\"mistake\"}",
                "Cancel order ORD-1001", PendingActionStatus.AWAITING_CONFIRMATION,
                NOW, NOW.plus(Duration.ofMinutes(10)), null, null);
    }

    @Test
    void proposingWritesARowAndChangesNothingElse() {
        PendingAction proposal = service(fixedClock()).propose(CONVERSATION, ADA, ActionType.CANCEL_ORDER,
                "cancelOrder", ORDER_ID, "ORD-1001", Map.of("orderNumber", "ORD-1001"),
                "Cancel order ORD-1001");

        assertThat(proposal.status()).isEqualTo(PendingActionStatus.AWAITING_CONFIRMATION);
        assertThat(proposal.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        verify(repository).insert(any(PendingAction.class));
        verify(executor, never()).execute(any());
    }

    @Test
    void approvingClaimsTheTokenBeforeDoingAnyWork() {
        when(repository.findById(TOKEN)).thenReturn(Optional.of(awaiting(ADA)));
        when(repository.transition(eq(TOKEN), eq(PendingActionStatus.AWAITING_CONFIRMATION),
                eq(PendingActionStatus.EXECUTING), any())).thenReturn(1);
        when(executor.execute(any())).thenReturn(new ActionOutcome("Order ORD-1001 is cancelled.", "payload"));

        ConfirmationOutcome outcome = service(fixedClock()).decide(ADA, TOKEN, ConfirmationDecision.APPROVE);

        assertThat(outcome.status()).isEqualTo(PendingActionStatus.EXECUTED);
        assertThat(outcome.message()).contains("cancelled");
        var order = org.mockito.Mockito.inOrder(repository, executor);
        order.verify(repository).transition(TOKEN, PendingActionStatus.AWAITING_CONFIRMATION,
                PendingActionStatus.EXECUTING, NOW);
        order.verify(executor).execute(any());
    }

    @Test
    void aSecondConfirmationForTheSameTokenLosesTheClaimAndExecutesNothing() {
        when(repository.findById(TOKEN)).thenReturn(Optional.of(awaiting(ADA)));
        // The compare-and-set matched no rows: another request already claimed it.
        when(repository.transition(eq(TOKEN), eq(PendingActionStatus.AWAITING_CONFIRMATION),
                eq(PendingActionStatus.EXECUTING), any())).thenReturn(0);

        assertThatThrownBy(() -> service(fixedClock()).decide(ADA, TOKEN, ConfirmationDecision.APPROVE))
                .isInstanceOf(ConfirmationErrors.AlreadyDecided.class);

        verify(executor, never()).execute(any());
    }

    @Test
    void anotherCustomersTokenIsReportedAsNotFoundAndAudited() {
        when(repository.findById(TOKEN)).thenReturn(Optional.of(awaiting(BLAKE)));

        assertThatThrownBy(() -> service(fixedClock()).decide(ADA, TOKEN, ConfirmationDecision.APPROVE))
                .isInstanceOf(ConfirmationErrors.NotFound.class);

        verify(executor, never()).execute(any());
        ArgumentCaptor<ToolInvocation> captor = ArgumentCaptor.forClass(ToolInvocation.class);
        verify(auditWriter).write(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo(ToolOutcome.NOT_FOUND);
        assertThat(captor.getValue().detail()).contains("different customer");
    }

    @Test
    void anExpiredTokenIsMarkedExpiredRatherThanLeftDangling() {
        when(repository.findById(TOKEN)).thenReturn(Optional.of(awaiting(ADA)));
        Clock elevenMinutesLater = clockAt(NOW.plus(Duration.ofMinutes(11)));

        assertThatThrownBy(() -> service(elevenMinutesLater).decide(ADA, TOKEN, ConfirmationDecision.APPROVE))
                .isInstanceOf(ConfirmationErrors.Expired.class);

        verify(repository).transition(eq(TOKEN), eq(PendingActionStatus.AWAITING_CONFIRMATION),
                eq(PendingActionStatus.EXPIRED), any(Instant.class));
        verify(executor, never()).execute(any());
    }

    @Test
    void rejectingClosesTheProposalWithoutTouchingTheOrder() {
        when(repository.findById(TOKEN)).thenReturn(Optional.of(awaiting(ADA)));
        when(repository.transition(eq(TOKEN), eq(PendingActionStatus.AWAITING_CONFIRMATION),
                eq(PendingActionStatus.REJECTED), any())).thenReturn(1);

        ConfirmationOutcome outcome = service(fixedClock()).decide(ADA, TOKEN, ConfirmationDecision.REJECT);

        assertThat(outcome.status()).isEqualTo(PendingActionStatus.REJECTED);
        assertThat(outcome.message()).contains("Nothing was changed");
        verify(executor, never()).execute(any());
    }

    @Test
    void anActionThatStoppedBeingLegalBetweenProposalAndApprovalFailsTheProposal() {
        // The warehouse shipped the order while the customer was reading the summary.
        when(repository.findById(TOKEN)).thenReturn(Optional.of(awaiting(ADA)));
        when(repository.transition(eq(TOKEN), eq(PendingActionStatus.AWAITING_CONFIRMATION),
                eq(PendingActionStatus.EXECUTING), any())).thenReturn(1);
        when(executor.execute(any())).thenThrow(
                new ActionNotAllowedException("ORDER_ALREADY_SHIPPED", "Order ORD-1001 has already shipped."));

        assertThatThrownBy(() -> service(fixedClock()).decide(ADA, TOKEN, ConfirmationDecision.APPROVE))
                .isInstanceOf(ActionNotAllowedException.class);

        verify(repository).transition(TOKEN, PendingActionStatus.EXECUTING, PendingActionStatus.FAILED, NOW);
    }

    @Test
    void anUnknownTokenIsNotFound() {
        when(repository.findById(TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(fixedClock()).decide(ADA, TOKEN, ConfirmationDecision.APPROVE))
                .isInstanceOf(ConfirmationErrors.NotFound.class);
    }
}
