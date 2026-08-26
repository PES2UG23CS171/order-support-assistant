package com.acme.ordersupport.rag;

/**
 * A chunk that came back from the vector store, with the similarity that put it
 * there. This is what becomes a citation on the API response, which is why the
 * score travels with it: "the bot cited a policy" is only useful if you can also
 * see how well it actually matched.
 */
public record RetrievedPolicy(String documentId, String policyId, String title, String section,
                              String version, String text, double score) {

    /** Short label used in the prompt's context block and echoed back in citations. */
    public String reference() {
        return policyId + "#" + section;
    }
}
