package com.acme.ordersupport.agent.audit;

public enum ToolOutcome {
    /** Read completed, or a confirmed action was executed. */
    SUCCESS,
    /** The model asked for a state change; a proposal was queued instead. */
    CONFIRMATION_REQUIRED,
    /** The order does not exist, or belongs to someone else. */
    NOT_FOUND,
    /** A business rule refused it -- outside the return window, already shipped. */
    NOT_ALLOWED,
    /** Anything unexpected. */
    ERROR
}
