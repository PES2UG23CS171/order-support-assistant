package com.acme.ordersupport.agent.confirm;

import com.acme.ordersupport.order.OrderService;
import com.acme.ordersupport.order.ReturnService;
import com.acme.ordersupport.order.model.CancellationResult;
import com.acme.ordersupport.order.model.ReturnResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * The only path from a queued proposal to a real state change.
 *
 * <p>It is reached from exactly one place -- {@link ConfirmationService} handling
 * an authenticated confirmation request -- and it re-reads the customer id from
 * the stored row rather than from anything in the current request body, so a
 * confirmation cannot be redirected onto a different account by its payload.
 */
@Component
public class ActionExecutor {

    private final OrderService orderService;
    private final ReturnService returnService;
    private final ObjectMapper objectMapper;

    public ActionExecutor(OrderService orderService, ReturnService returnService, ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.returnService = returnService;
        this.objectMapper = objectMapper;
    }

    public ActionOutcome execute(PendingAction action) {
        String reason = argument(action, "reason", "Requested by the customer in support chat");
        return switch (action.actionType()) {
            case CANCEL_ORDER -> {
                CancellationResult result =
                        orderService.cancel(action.customerId(), action.orderNumber(), reason);
                yield new ActionOutcome(result.message(), result);
            }
            case INITIATE_RETURN -> {
                ReturnResult result =
                        returnService.initiateReturn(action.customerId(), action.orderNumber(), reason);
                yield new ActionOutcome(result.message(), result);
            }
        };
    }

    private String argument(PendingAction action, String field, String fallback) {
        try {
            JsonNode node = objectMapper.readTree(action.argumentsJson()).get(field);
            return node != null && !node.isNull() && !node.asText().isBlank() ? node.asText() : fallback;
        }
        catch (Exception ex) {
            return fallback;
        }
    }
}
