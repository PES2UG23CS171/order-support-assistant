package com.acme.ordersupport.order.model;

/**
 * The state-changing operations the assistant may <em>propose</em>. Read-only
 * tools have no entry here, which is what makes "does this tool need a human?"
 * a lookup rather than a judgement call.
 */
public enum ActionType {
    CANCEL_ORDER,
    INITIATE_RETURN
}
