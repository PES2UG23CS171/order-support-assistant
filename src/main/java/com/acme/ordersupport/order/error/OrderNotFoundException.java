package com.acme.ordersupport.order.error;

/**
 * Thrown when an order does not exist <em>or</em> belongs to a different customer.
 *
 * <p>The two cases are deliberately indistinguishable to the caller. Returning
 * "forbidden" for someone else's order confirms that the order number is real,
 * which turns a chat box into an order-number oracle. The distinction is kept in
 * the audit log, where it belongs.
 */
public class OrderNotFoundException extends RuntimeException {

    private final String orderNumber;

    public OrderNotFoundException(String orderNumber) {
        super("No order %s was found for this customer.".formatted(orderNumber));
        this.orderNumber = orderNumber;
    }

    public String orderNumber() {
        return orderNumber;
    }
}
