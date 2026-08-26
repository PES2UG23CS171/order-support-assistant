package com.acme.ordersupport.agent.model;

import com.acme.ordersupport.agent.audit.AuditedResult;
import com.acme.ordersupport.agent.audit.ToolOutcome;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A tool declining to do something, in a shape the model can explain to a customer.
 *
 * @param code stable reason code, mirrored in the audit log and asserted on in tests
 * @param reason customer-safe wording, already written by the service layer
 */
public record ToolRefusal(String status, String code, String orderNumber, String reason)
        implements ToolResult, AuditedResult {

    public static ToolRefusal notFound(String orderNumber) {
        return new ToolRefusal("NOT_FOUND", "ORDER_NOT_FOUND", orderNumber,
                ("No order %s is associated with this account. Double-check the number, and note that "
                 + "orders placed on another account cannot be seen from here.").formatted(orderNumber));
    }

    public static ToolRefusal notAllowed(String orderNumber, String code, String reason) {
        return new ToolRefusal("NOT_ALLOWED", code, orderNumber, reason);
    }

    @Override
    @JsonIgnore
    public ToolOutcome outcome() {
        return "NOT_FOUND".equals(status) ? ToolOutcome.NOT_FOUND : ToolOutcome.NOT_ALLOWED;
    }

    @Override
    @JsonIgnore
    public String detail() {
        return code + ": " + reason;
    }
}
