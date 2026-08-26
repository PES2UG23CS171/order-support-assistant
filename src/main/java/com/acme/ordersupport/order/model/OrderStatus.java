package com.acme.ordersupport.order.model;

public enum OrderStatus {
    CREATED,
    PAID,
    PROCESSING,
    SHIPPED,
    IN_TRANSIT,
    DELIVERED,
    CANCELLED,
    RETURN_REQUESTED,
    RETURNED,
    REFUNDED;

    /**
     * Cancellable up to the moment a shipping label exists. Mirrors
     * "Cancellation window" in order-cancellation-policy.md -- the rule the
     * assistant quotes and the rule the service enforces are the same rule.
     */
    public boolean cancellable() {
        return this == CREATED || this == PAID || this == PROCESSING;
    }

    /** Whether money has already been captured, and therefore whether cancelling owes a refund. */
    public boolean paymentCaptured() {
        return this != CREATED;
    }
}
