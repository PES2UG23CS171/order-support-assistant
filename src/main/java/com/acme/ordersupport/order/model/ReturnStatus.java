package com.acme.ordersupport.order.model;

public enum ReturnStatus {
    REQUESTED,
    LABEL_SENT,
    IN_TRANSIT,
    RECEIVED,
    INSPECTED,
    COMPLETED,
    REJECTED,
    EXPIRED;

    /** An open RMA blocks a second return request against the same order. */
    public boolean open() {
        return this == REQUESTED || this == LABEL_SENT || this == IN_TRANSIT
                || this == RECEIVED || this == INSPECTED;
    }
}
