package com.acme.ordersupport.agent;

import java.util.UUID;

/** Unknown conversation, or one belonging to someone else -- the same answer for both. */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(UUID conversationId) {
        super("No conversation %s was found for this customer.".formatted(conversationId));
    }
}
