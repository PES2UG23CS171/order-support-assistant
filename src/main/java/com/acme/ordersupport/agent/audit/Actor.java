package com.acme.ordersupport.agent.audit;

/** Who caused a tool to run. The distinction is the whole point of the audit table. */
public enum Actor {
    /** The model chose to call the tool during a chat turn. */
    LLM_TOOL_CALL,
    /** A human clicked confirm and the queued action was executed. */
    HUMAN_CONFIRMATION,
    /** A first-party client called the REST API directly. */
    REST_CLIENT
}
