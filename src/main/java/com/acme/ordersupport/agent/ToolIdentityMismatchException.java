package com.acme.ordersupport.agent;

/**
 * The customer on the tool context is not the authenticated customer.
 *
 * <p>This should be unreachable. It is checked anyway, because the alternative to
 * an exception here is a tool quietly running against the wrong account.
 */
public class ToolIdentityMismatchException extends RuntimeException {

    public ToolIdentityMismatchException(String message) {
        super(message);
    }
}
