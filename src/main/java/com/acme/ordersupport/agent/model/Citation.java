package com.acme.ordersupport.agent.model;

/**
 * A policy passage the answer actually leaned on.
 *
 * @param marker the bracketed number as it appears in the reply text, so a UI can
 *               turn "[2]" into a link without re-deriving anything
 */
public record Citation(int marker, String policyId, String title, String section, String version,
                       double score, String excerpt) {
}
