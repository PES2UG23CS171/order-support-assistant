package com.acme.ordersupport.agent;

import static com.acme.ordersupport.testsupport.Fixtures.ADA;
import static com.acme.ordersupport.testsupport.Fixtures.BLAKE;
import static com.acme.ordersupport.testsupport.Fixtures.NOW;
import static com.acme.ordersupport.testsupport.Fixtures.deliveredDaysAgo;
import static com.acme.ordersupport.testsupport.Fixtures.fixedClock;
import static com.acme.ordersupport.testsupport.Fixtures.line;
import static com.acme.ordersupport.testsupport.Fixtures.orderView;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ordersupport.agent.audit.AuditRepository;
import com.acme.ordersupport.agent.audit.AuditService;
import com.acme.ordersupport.agent.audit.AuditWriter;
import com.acme.ordersupport.agent.audit.ToolInvocation;
import com.acme.ordersupport.agent.audit.ToolOutcome;
import com.acme.ordersupport.agent.confirm.ConfirmationService;
import com.acme.ordersupport.agent.confirm.PendingAction;
import com.acme.ordersupport.agent.confirm.PendingActionStatus;
import com.acme.ordersupport.agent.model.ActionProposal;
import com.acme.ordersupport.agent.model.OrderSnapshot;
import com.acme.ordersupport.agent.model.ToolRefusal;
import com.acme.ordersupport.agent.model.ToolResult;
import com.acme.ordersupport.config.CurrentCustomer;
import com.acme.ordersupport.order.OrderService;
import com.acme.ordersupport.order.RefundService;
import com.acme.ordersupport.order.ReturnService;
import com.acme.ordersupport.order.error.OrderNotFoundException;
import com.acme.ordersupport.order.model.ActionType;
import com.acme.ordersupport.order.model.Eligibility;
import com.acme.ordersupport.order.model.OrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The properties that make the tool layer safe, asserted directly: action tools
 * never mutate, refusals come back as data the model can explain, and identity
 * cannot be supplied by the caller.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupportToolsTest {

    private static final UUID CONVERSATION = UUID.randomUUID();

    @Mock
    private OrderService orderService;

    @Mock
    private ReturnService returnService;

    @Mock
    private RefundService refundService;

    @Mock
    private ConfirmationService confirmations;

    @Mock
    private AuditWriter auditWriter;

    @Mock
    private AuditRepository auditRepository;

    @Mock
    private CurrentCustomer currentCustomer;

    private SupportTools tools;

    private final ToolContext toolContext = new ToolContext(
            Map.of(SupportSession.CONTEXT_KEY, new SupportSession(CONVERSATION, ADA)));

    @BeforeEach
    void setUp() {
        AuditService audit = new AuditService(auditWriter, auditRepository, new ObjectMapper(), fixedClock());
        tools = new SupportTools(orderService, returnService, refundService, confirmations, audit,
                currentCustomer);
        when(currentCustomer.requireId()).thenReturn(ADA);
    }

    private void givenDeliveredOrder() {
        var order = orderView("ORD-1003", OrderStatus.DELIVERED,
                List.of(line("HP-1", "AUDIO", true, "89.99")), deliveredDaysAgo(3));
        when(orderService.lookup(ADA, "ORD-1003")).thenReturn(order);
        when(orderService.assessCancellation(ADA, "ORD-1003"))
                .thenReturn(Eligibility.denied("ORDER_ALREADY_SHIPPED", "Already delivered."));
        when(returnService.assessReturn(ADA, "ORD-1003"))
                .thenReturn(Eligibility.allowed("Delivered 3 days ago, inside its 30-day window."));
    }

    @Test
    void lookupCarriesTheEligibilityVerdictsSoTheModelNeverDerivesThem() {
        givenDeliveredOrder();

        OrderSnapshot snapshot = (OrderSnapshot) tools.lookupOrder("ORD-1003", toolContext);

        assertThat(snapshot.status()).isEqualTo("DELIVERED");
        assertThat(snapshot.cancellable()).isFalse();
        assertThat(snapshot.returnable()).isTrue();
        assertThat(snapshot.returnNote()).contains("30-day window");
        assertThat(snapshot.delivery().carrier()).isEqualTo("UPS");
    }

    @Test
    void cancelOrderProposesAndDoesNotCancel() {
        var order = orderView("ORD-1001", OrderStatus.PROCESSING,
                List.of(line("KB-1", "PERIPHERALS", true, "249.00")), null);
        when(orderService.lookup(ADA, "ORD-1001")).thenReturn(order);
        when(orderService.assessCancellation(ADA, "ORD-1001"))
                .thenReturn(Eligibility.allowed("Not shipped yet."));
        when(confirmations.propose(any(), any(), eq(ActionType.CANCEL_ORDER), eq("cancelOrder"), any(),
                eq("ORD-1001"), any(), anyString())).thenReturn(pending(ActionType.CANCEL_ORDER, "ORD-1001"));

        ToolResult result = tools.cancelOrder("ORD-1001", "ordered by mistake", toolContext);

        assertThat(result).isInstanceOf(ActionProposal.class);
        assertThat(((ActionProposal) result).status()).isEqualTo("CONFIRMATION_REQUIRED");
        assertThat(((ActionProposal) result).note()).contains("has not been performed");
        // The one assertion that matters: the service method that changes state
        // was never reached.
        verify(orderService, never()).cancel(any(), anyString(), anyString());
    }

    @Test
    void initiateReturnProposesAndDoesNotOpenTheReturn() {
        givenDeliveredOrder();
        when(confirmations.propose(any(), any(), eq(ActionType.INITIATE_RETURN), eq("initiateReturn"), any(),
                eq("ORD-1003"), any(), anyString()))
                .thenReturn(pending(ActionType.INITIATE_RETURN, "ORD-1003"));

        ToolResult result = tools.initiateReturn("ORD-1003", "it arrived damaged", toolContext);

        assertThat(result).isInstanceOf(ActionProposal.class);
        verify(returnService, never()).initiateReturn(any(), anyString(), anyString());
    }

    @Test
    void anIneligibleActionRefusesWithTheReasonAndQueuesNothing() {
        var order = orderView("ORD-1002", OrderStatus.SHIPPED,
                List.of(line("MON-1", "PERIPHERALS", true, "129.50")), null);
        when(orderService.lookup(ADA, "ORD-1002")).thenReturn(order);
        when(orderService.assessCancellation(ADA, "ORD-1002")).thenReturn(
                Eligibility.denied("ORDER_ALREADY_SHIPPED", "Order ORD-1002 has already left the warehouse."));

        ToolResult result = tools.cancelOrder("ORD-1002", "too late", toolContext);

        assertThat(result).isInstanceOf(ToolRefusal.class);
        assertThat(((ToolRefusal) result).code()).isEqualTo("ORDER_ALREADY_SHIPPED");
        assertThat(((ToolRefusal) result).reason()).contains("already left the warehouse");
        verifyNoInteractions(confirmations);
    }

    @Test
    void anotherCustomersOrderComesBackAsNotFoundRatherThanAnError() {
        when(orderService.lookup(ADA, "ORD-2001")).thenThrow(new OrderNotFoundException("ORD-2001"));

        ToolResult result = tools.lookupOrder("ORD-2001", toolContext);

        assertThat(result).isInstanceOf(ToolRefusal.class);
        assertThat(((ToolRefusal) result).status()).isEqualTo("NOT_FOUND");
        assertThat(((ToolRefusal) result).reason()).contains("another account cannot be seen");
    }

    @Test
    void everyCallIsAuditedWithAnOutcomeDerivedFromWhatHappened() {
        when(orderService.lookup(ADA, "ORD-2001")).thenThrow(new OrderNotFoundException("ORD-2001"));

        tools.lookupOrder("ORD-2001", toolContext);

        ArgumentCaptor<ToolInvocation> captor = ArgumentCaptor.forClass(ToolInvocation.class);
        verify(auditWriter).write(captor.capture());
        ToolInvocation logged = captor.getValue();
        assertThat(logged.toolName()).isEqualTo("lookupOrder");
        assertThat(logged.outcome()).isEqualTo(ToolOutcome.NOT_FOUND);
        assertThat(logged.arguments()).isEqualTo("{\"orderNumber\":\"ORD-2001\"}");
        assertThat(logged.conversationId()).isEqualTo(CONVERSATION);
    }

    @Test
    void aProposalIsAuditedAsRequiringConfirmationAndLinkedToItsToken() {
        var order = orderView("ORD-1001", OrderStatus.PROCESSING,
                List.of(line("KB-1", "PERIPHERALS", true, "249.00")), null);
        when(orderService.lookup(ADA, "ORD-1001")).thenReturn(order);
        when(orderService.assessCancellation(ADA, "ORD-1001")).thenReturn(Eligibility.allowed("ok"));
        PendingAction proposal = pending(ActionType.CANCEL_ORDER, "ORD-1001");
        when(confirmations.propose(any(), any(), any(), anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(proposal);

        tools.cancelOrder("ORD-1001", "mistake", toolContext);

        ArgumentCaptor<ToolInvocation> captor = ArgumentCaptor.forClass(ToolInvocation.class);
        verify(auditWriter).write(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo(ToolOutcome.CONFIRMATION_REQUIRED);
        assertThat(captor.getValue().pendingActionId()).isEqualTo(proposal.id());
    }

    @Test
    void aToolContextThatDisagreesWithTheAuthenticatedCustomerIsRefused() {
        // Unreachable through the HTTP path; checked so that it stays unreachable
        // if the chat call ever moves off the request thread.
        when(currentCustomer.requireId()).thenReturn(BLAKE);

        assertThatThrownBy(() -> tools.lookupOrder("ORD-1003", toolContext))
                .isInstanceOf(ToolIdentityMismatchException.class);

        verifyNoInteractions(orderService);
    }

    @Test
    void aToolInvokedWithoutASessionFailsRatherThanGuessing() {
        assertThatThrownBy(() -> tools.lookupOrder("ORD-1003", new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without a support session");
    }

    private static PendingAction pending(ActionType type, String orderNumber) {
        return new PendingAction(UUID.randomUUID(), CONVERSATION, ADA, type,
                type == ActionType.CANCEL_ORDER ? "cancelOrder" : "initiateReturn", UUID.randomUUID(),
                orderNumber, "{}", "summary", PendingActionStatus.AWAITING_CONFIRMATION, NOW,
                NOW.plus(Duration.ofMinutes(10)), null, null);
    }
}
