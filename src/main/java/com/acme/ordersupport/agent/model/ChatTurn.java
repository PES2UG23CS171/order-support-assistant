package com.acme.ordersupport.agent.model;

import java.util.List;
import java.util.UUID;

/**
 * One exchange, and everything that can be said about how it was produced:
 * what was cited, which tools ran and how they ended, whether a guardrail fired,
 * and whether a human now has something to approve.
 *
 * @param retrievedPassages every passage put in front of the model, whether or not
 *                          the answer used it -- {@code citations} is the subset it
 *                          actually marked, and the gap between the two is the
 *                          interesting number when tuning retrieval
 */
public record ChatTurn(UUID conversationId, String reply, List<Citation> citations,
                       List<Citation> retrievedPassages, List<ToolCallSummary> toolCalls,
                       PendingActionSummary pendingAction, String guardrailTriggered,
                       boolean modelInvoked) {
}
