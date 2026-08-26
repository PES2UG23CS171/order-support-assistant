package com.acme.ordersupport.agent.confirm;

/**
 * What actually happened once a human said yes.
 *
 * @param message customer-facing wording
 * @param payload the service-layer result, serialised onto the pending action row
 */
public record ActionOutcome(String message, Object payload) {
}
