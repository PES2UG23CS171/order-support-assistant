package com.acme.ordersupport.order.error;

/** The optimistic lock on an order lost a race with another writer. */
public class ConcurrentUpdateException extends RuntimeException {

    public ConcurrentUpdateException(String orderNumber) {
        super("Order %s was modified by another request. Please retry.".formatted(orderNumber));
    }
}
