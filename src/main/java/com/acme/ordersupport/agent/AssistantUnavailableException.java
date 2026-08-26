package com.acme.ordersupport.agent;

/** No chat model is configured, so the conversational endpoint cannot serve a turn. */
public class AssistantUnavailableException extends RuntimeException {

    public AssistantUnavailableException() {
        super("The chat assistant is not configured on this instance. Order lookups, returns, refunds "
              + "and policy search are unaffected.");
    }
}
