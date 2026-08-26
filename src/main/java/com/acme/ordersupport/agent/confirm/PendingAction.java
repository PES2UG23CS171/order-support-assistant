package com.acme.ordersupport.agent.confirm;

import com.acme.ordersupport.order.model.ActionType;
import java.time.Instant;
import java.util.UUID;

/**
 * A state change the model asked for and did not get.
 *
 * <p>The row id is the confirmation token. Redeeming it requires an authenticated
 * HTTP call from the customer who owns it, which is why a compromised or
 * jailbroken prompt cannot turn into a cancelled order: the model's most
 * successful possible outcome is a row in this table.
 */
public record PendingAction(UUID id, UUID conversationId, UUID customerId, ActionType actionType,
                            String toolName, UUID orderId, String orderNumber, String argumentsJson,
                            String summary, PendingActionStatus status, Instant createdAt,
                            Instant expiresAt, Instant decidedAt, String resultJson) {

    public boolean expiredAt(Instant now) {
        return now.isAfter(expiresAt);
    }
}
