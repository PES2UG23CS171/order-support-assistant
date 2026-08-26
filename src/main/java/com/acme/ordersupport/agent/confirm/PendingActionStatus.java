package com.acme.ordersupport.agent.confirm;

public enum PendingActionStatus {
    /** Proposed by the model, waiting on the customer. */
    AWAITING_CONFIRMATION,
    /** Claimed by a confirmation request. A second request cannot claim it again. */
    EXECUTING,
    EXECUTED,
    REJECTED,
    EXPIRED,
    FAILED
}
