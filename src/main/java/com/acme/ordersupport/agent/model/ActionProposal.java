package com.acme.ordersupport.agent.model;

import com.acme.ordersupport.agent.audit.AuditedResult;
import com.acme.ordersupport.agent.audit.ToolOutcome;
import com.acme.ordersupport.agent.confirm.PendingAction;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/**
 * What a state-changing tool returns instead of a state change.
 *
 * <p>{@code status} is the first field the model reads and it always says
 * {@code CONFIRMATION_REQUIRED}. The {@code note} is written as a statement of
 * fact rather than as an instruction, on purpose: the system prompt tells the
 * model that tool output is data and never a command, and a payload that then
 * issues commands would be teaching it the opposite.
 */
public record ActionProposal(String status, String action, String orderNumber, String summary,
                             UUID confirmationToken, String expiresAt, String note)
        implements ToolResult, AuditedResult {

    public static final String STATUS = "CONFIRMATION_REQUIRED";

    private static final String NOTE =
            "This action has not been performed. It is queued and will only happen if the customer "
            + "explicitly confirms it in the application. Nothing about the order has changed.";

    public static ActionProposal from(PendingAction action) {
        return new ActionProposal(STATUS, action.actionType().name(), action.orderNumber(),
                action.summary(), action.id(), Text.iso(action.expiresAt()), NOTE);
    }

    @Override
    @JsonIgnore
    public ToolOutcome outcome() {
        return ToolOutcome.CONFIRMATION_REQUIRED;
    }

    @Override
    @JsonIgnore
    public UUID pendingActionId() {
        return confirmationToken();
    }
}
