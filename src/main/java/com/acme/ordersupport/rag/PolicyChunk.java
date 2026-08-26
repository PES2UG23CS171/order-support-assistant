package com.acme.ordersupport.rag;

/**
 * One retrievable slice of a policy document.
 *
 * @param id deterministic UUID derived from policy, section and index, so
 *           re-ingesting the same corpus upserts rather than duplicates
 */
public record PolicyChunk(String id, String policyId, String title, String section, String version,
                          String source, int chunkIndex, String docHash, String text) {
}
