package com.acme.ordersupport.agent.audit;

import java.util.UUID;

/**
 * Implemented by tool payloads that are not a plain success.
 *
 * <p>Tools return refusals as values rather than throwing them. A business
 * refusal -- outside the return window, already shipped, not your order -- is an
 * ordinary outcome that the model needs to read and explain, and routing it
 * through an exception would make the behaviour depend on the framework's
 * exception policy: whether the message reaches the model or the whole chat turn
 * fails. Exceptions stay for things that are genuinely broken.
 */
public interface AuditedResult {

    ToolOutcome outcome();

    default String detail() {
        return null;
    }

    default UUID pendingActionId() {
        return null;
    }
}
