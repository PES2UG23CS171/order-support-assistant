package com.acme.ordersupport.agent.model;

import com.acme.ordersupport.agent.confirm.PendingAction;
import java.time.Instant;
import java.util.UUID;

/**
 * The confirmation prompt the client renders as a pair of buttons. Its presence on
 * a response is the signal that the turn ended in a proposal, not an action.
 */
public record PendingActionSummary(UUID confirmationToken, String action, String orderNumber,
                                   String summary, Instant expiresAt) {

    public static PendingActionSummary from(PendingAction action) {
        return new PendingActionSummary(action.id(), action.actionType().name(), action.orderNumber(),
                action.summary(), action.expiresAt());
    }
}
