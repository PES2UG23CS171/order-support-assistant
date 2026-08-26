package com.acme.ordersupport.agent;

import com.acme.ordersupport.agent.audit.Actor;
import com.acme.ordersupport.agent.audit.AuditService;
import com.acme.ordersupport.agent.confirm.ConfirmationService;
import com.acme.ordersupport.agent.confirm.PendingAction;
import com.acme.ordersupport.agent.model.ActionProposal;
import com.acme.ordersupport.agent.model.DeliverySnapshot;
import com.acme.ordersupport.agent.model.OrderSnapshot;
import com.acme.ordersupport.agent.model.RefundSnapshot;
import com.acme.ordersupport.agent.model.ToolRefusal;
import com.acme.ordersupport.agent.model.ToolResult;
import com.acme.ordersupport.config.CurrentCustomer;
import com.acme.ordersupport.order.OrderService;
import com.acme.ordersupport.order.RefundService;
import com.acme.ordersupport.order.ReturnService;
import com.acme.ordersupport.order.error.OrderNotFoundException;
import com.acme.ordersupport.order.model.ActionType;
import com.acme.ordersupport.order.model.Eligibility;
import com.acme.ordersupport.order.model.OrderView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The five tools the model can reach, and the boundary it cannot cross.
 *
 * <p>Three properties hold for every method here, and together they are the answer
 * to "how do you stop the AI doing something it shouldn't":
 *
 * <ol>
 *   <li><b>No database.</b> Not a repository, not a connection, not a SQL string.
 *       Every call goes through the same service methods the REST controllers use,
 *       so validation, authorization and optimistic locking apply identically
 *       whether the caller is a language model or the mobile app.</li>
 *   <li><b>No identity parameter.</b> No tool accepts a customer id, so none
 *       appears in the JSON schema the model is given, so there is nothing for it
 *       to hallucinate or be talked into. The acting customer comes from the
 *       authenticated session, cross-checked against the tool context.</li>
 *   <li><b>No state change.</b> {@code cancelOrder} does not cancel and
 *       {@code initiateReturn} does not open a return. They validate, then queue a
 *       proposal for a human to confirm. The best outcome available to a
 *       successfully jailbroken prompt is a row in {@code pending_actions}.</li>
 * </ol>
 */
@Component
public class SupportTools {

    private final OrderService orderService;
    private final ReturnService returnService;
    private final RefundService refundService;
    private final ConfirmationService confirmations;
    private final AuditService audit;
    private final CurrentCustomer currentCustomer;

    public SupportTools(OrderService orderService, ReturnService returnService, RefundService refundService,
                        ConfirmationService confirmations, AuditService audit, CurrentCustomer currentCustomer) {
        this.orderService = orderService;
        this.returnService = returnService;
        this.refundService = refundService;
        this.confirmations = confirmations;
        this.audit = audit;
        this.currentCustomer = currentCustomer;
    }

    @Tool(name = "lookupOrder", description = """
            Look up one of the signed-in customer's orders by its order number, for example ORD-1001.
            Returns the order status, the items on it, delivery information if it has shipped, and
            whether the order can currently be cancelled or returned along with the reason.
            Use this first whenever the customer mentions a specific order.""")
    public ToolResult lookupOrder(
            @ToolParam(description = "The order number as the customer gave it, for example ORD-1001")
            String orderNumber,
            ToolContext toolContext) {

        SupportSession session = session(toolContext);
        return audit.around("lookupOrder", Actor.LLM_TOOL_CALL, session, args("orderNumber", orderNumber),
                () -> {
                    try {
                        return snapshot(session.customerId(), orderNumber);
                    }
                    catch (OrderNotFoundException ex) {
                        return ToolRefusal.notFound(orderNumber);
                    }
                });
    }

    @Tool(name = "trackDelivery", description = """
            Get the current delivery state of one of the signed-in customer's orders: carrier, tracking
            number, latest known location, estimated delivery date, and the delivered date if it has
            arrived. Use this for "where is my order" and "has it been delivered" questions.""")
    public ToolResult trackDelivery(
            @ToolParam(description = "The order number to track, for example ORD-1002")
            String orderNumber,
            ToolContext toolContext) {

        SupportSession session = session(toolContext);
        return audit.around("trackDelivery", Actor.LLM_TOOL_CALL, session, args("orderNumber", orderNumber),
                () -> {
                    try {
                        return DeliverySnapshot.from(orderService.lookup(session.customerId(), orderNumber));
                    }
                    catch (OrderNotFoundException ex) {
                        return ToolRefusal.notFound(orderNumber);
                    }
                });
    }

    @Tool(name = "checkRefundStatus", description = """
            List every refund opened against one of the signed-in customer's orders, with its amount,
            state (PENDING, PROCESSING, SETTLED or FAILED), the method it is going back to, and the
            expected settlement date where one is known. Use this for "where is my money" questions.""")
    public ToolResult checkRefundStatus(
            @ToolParam(description = "The order number whose refunds should be listed, for example ORD-1006")
            String orderNumber,
            ToolContext toolContext) {

        SupportSession session = session(toolContext);
        return audit.around("checkRefundStatus", Actor.LLM_TOOL_CALL, session,
                args("orderNumber", orderNumber), () -> {
                    try {
                        return RefundSnapshot.from(orderNumber,
                                refundService.refundsForOrder(session.customerId(), orderNumber));
                    }
                    catch (OrderNotFoundException ex) {
                        return ToolRefusal.notFound(orderNumber);
                    }
                });
    }

    @Tool(name = "cancelOrder", description = """
            Prepare a cancellation of one of the signed-in customer's orders. This does NOT cancel the
            order. It checks the order is still cancellable and returns a proposal with a confirmation
            token that the customer has to approve in the application before anything happens. Tell the
            customer what will happen and that you are waiting on their confirmation; do not tell them
            it is done. If the order cannot be cancelled the result explains why instead.""")
    public ToolResult cancelOrder(
            @ToolParam(description = "The order number to cancel, for example ORD-1001")
            String orderNumber,
            @ToolParam(description = "The customer's reason for cancelling, in their own words", required = false)
            String reason,
            ToolContext toolContext) {

        SupportSession session = session(toolContext);
        Map<String, Object> arguments = args("orderNumber", orderNumber, "reason", reason);
        return audit.around("cancelOrder", Actor.LLM_TOOL_CALL, session, arguments, () -> {
            try {
                OrderView order = orderService.lookup(session.customerId(), orderNumber);
                Eligibility eligibility = orderService.assessCancellation(session.customerId(), orderNumber);
                if (!eligibility.allowed()) {
                    return ToolRefusal.notAllowed(orderNumber, eligibility.code(), eligibility.explanation());
                }

                String summary = ("Cancel order %s (%s %s, currently %s). A full refund to the original "
                                  + "payment method will be opened. This cannot be undone.")
                        .formatted(orderNumber, order.currency(), order.totalAmount(), order.status());
                PendingAction proposal = confirmations.propose(session.conversationId(), session.customerId(),
                        ActionType.CANCEL_ORDER, "cancelOrder", order.id(), orderNumber, arguments, summary);
                return ActionProposal.from(proposal);
            }
            catch (OrderNotFoundException ex) {
                return ToolRefusal.notFound(orderNumber);
            }
        });
    }

    @Tool(name = "initiateReturn", description = """
            Prepare a return for one of the signed-in customer's delivered orders. This does NOT open the
            return. It checks the return window, the item's returnability and whether a return is already
            open, then returns a proposal with a confirmation token the customer has to approve before
            anything happens. Tell the customer what will happen and that you are waiting on their
            confirmation; do not tell them the return is open. If a return is not possible the result
            explains why instead.""")
    public ToolResult initiateReturn(
            @ToolParam(description = "The order number to return, for example ORD-1003")
            String orderNumber,
            @ToolParam(description = "Why the customer wants to return it, in their own words. "
                                     + "Faults and damage waive the return shipping fee.")
            String reason,
            ToolContext toolContext) {

        SupportSession session = session(toolContext);
        Map<String, Object> arguments = args("orderNumber", orderNumber, "reason", reason);
        return audit.around("initiateReturn", Actor.LLM_TOOL_CALL, session, arguments, () -> {
            try {
                OrderView order = orderService.lookup(session.customerId(), orderNumber);
                Eligibility eligibility = returnService.assessReturn(session.customerId(), orderNumber);
                if (!eligibility.allowed()) {
                    return ToolRefusal.notAllowed(orderNumber, eligibility.code(), eligibility.explanation());
                }

                String summary = ("Open a return on order %s (%s). %s A prepaid label will be emailed and the "
                                  + "refund is released once the item is received and inspected.")
                        .formatted(orderNumber, order.currency() + " " + order.totalAmount(),
                                eligibility.explanation());
                PendingAction proposal = confirmations.propose(session.conversationId(), session.customerId(),
                        ActionType.INITIATE_RETURN, "initiateReturn", order.id(), orderNumber, arguments,
                        summary);
                return ActionProposal.from(proposal);
            }
            catch (OrderNotFoundException ex) {
                return ToolRefusal.notFound(orderNumber);
            }
        });
    }

    private OrderSnapshot snapshot(UUID customerId, String orderNumber) {
        OrderView order = orderService.lookup(customerId, orderNumber);
        Eligibility cancellation = orderService.assessCancellation(customerId, orderNumber);
        Eligibility returnable = returnService.assessReturn(customerId, orderNumber);
        return OrderSnapshot.from(order, cancellation.allowed(), cancellation.explanation(),
                returnable.allowed(), returnable.explanation());
    }

    /**
     * Resolves the turn and proves the two independent sources of identity agree.
     *
     * <p>The tool context is set by the agent when it builds the request; the
     * security context is set by the authentication filter. They are populated by
     * different code on different paths and should never disagree. If they do,
     * something has gone wrong that we would rather hear about as an exception
     * than discover later in the audit log.
     */
    private SupportSession session(ToolContext toolContext) {
        SupportSession session = SupportSession.from(toolContext);
        UUID authenticated = currentCustomer.requireId();
        if (!authenticated.equals(session.customerId())) {
            throw new ToolIdentityMismatchException(
                    "Tool context customer %s does not match the authenticated customer %s"
                            .formatted(session.customerId(), authenticated));
        }
        return session;
    }

    private static Map<String, Object> args(String key, Object value) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(key, value);
        return arguments;
    }

    private static Map<String, Object> args(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> arguments = args(key1, value1);
        arguments.put(key2, value2);
        return arguments;
    }
}
