package com.acme.ordersupport.agent.confirm;

import java.util.UUID;

public record ConfirmationOutcome(UUID token, PendingActionStatus status, String message, Object result) {
}
