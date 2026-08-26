package com.acme.ordersupport.guardrail;

/**
 * @param reply what the customer sees when a guard blocks. Written here rather
 *              than generated, because the point of blocking is that the model
 *              is not involved.
 */
public record GuardDecision(boolean blocked, String guardrail, String reason, String reply) {

    public static GuardDecision allow() {
        return new GuardDecision(false, null, null, null);
    }

    public static GuardDecision block(String guardrail, String reason, String reply) {
        return new GuardDecision(true, guardrail, reason, reply);
    }
}
