package com.acme.ordersupport.agent;

import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The turn a tool call belongs to.
 *
 * <p>Carried through Spring AI's {@link ToolContext}, which is passed to tools by
 * the framework and is never visible to the model -- it is not part of any tool's
 * JSON schema, so there is nothing for the model to fill in or overwrite.
 *
 * <p>The customer id is here as well as in the security context on purpose. Tools
 * assert the two agree ({@link ToolIdentityMismatchException}), which costs one
 * comparison and closes the gap that would open the day someone switches the chat
 * call to a reactive or thread-pooled path and the {@code ThreadLocal} behind the
 * security context stops following the work.
 */
public record SupportSession(UUID conversationId, UUID customerId) {

    public static final String CONTEXT_KEY = "supportSession";

    public Map<String, Object> asToolContext() {
        return Map.of(CONTEXT_KEY, this);
    }

    public static SupportSession from(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            throw new IllegalStateException("Tool invoked without a support session in its tool context");
        }
        Object session = toolContext.getContext().get(CONTEXT_KEY);
        if (!(session instanceof SupportSession supportSession)) {
            throw new IllegalStateException("Tool invoked without a support session in its tool context");
        }
        return supportSession;
    }
}
