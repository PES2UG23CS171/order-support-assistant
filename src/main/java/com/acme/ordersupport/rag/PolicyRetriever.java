package com.acme.ordersupport.rag;

import com.acme.ordersupport.config.AppProperties;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Similarity search over the policy corpus, plus the rendering of what came back
 * into a numbered context block.
 *
 * <p>The numbering is load-bearing. The model is told to answer only from the
 * numbered passages and to mark each claim with the number it came from, which
 * turns "cite your source" from a request into something the response parser can
 * check: a claim with no marker is a claim with no grounding, and the API surfaces
 * exactly the passages whose markers the model actually used.
 */
@Service
public class PolicyRetriever {

    private final VectorStore vectorStore;
    private final AppProperties properties;

    public PolicyRetriever(VectorStore vectorStore, AppProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public List<RetrievedPolicy> retrieve(String query) {
        return retrieve(query, properties.rag().topK(), properties.rag().similarityThreshold());
    }

    public List<RetrievedPolicy> retrieve(String query, int topK, double similarityThreshold) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build());
        return documents == null ? List.of()
                : documents.stream().filter(Objects::nonNull).map(PolicyRetriever::toRetrieved).toList();
    }

    private static RetrievedPolicy toRetrieved(Document document) {
        var metadata = document.getMetadata();
        return new RetrievedPolicy(
                document.getId(),
                String.valueOf(metadata.getOrDefault("policy_id", "unknown")),
                String.valueOf(metadata.getOrDefault("title", "Untitled policy")),
                String.valueOf(metadata.getOrDefault("section", "Overview")),
                String.valueOf(metadata.getOrDefault("version", "unversioned")),
                document.getText(),
                document.getScore() != null ? document.getScore() : 0d);
    }

    /**
     * Renders passages as {@code [1] ... [2] ...}. The indices are one-based and
     * match the citation markers the model is asked to emit.
     */
    public static String asContextBlock(List<RetrievedPolicy> passages) {
        if (passages.isEmpty()) {
            return "(no policy passages matched this question)";
        }
        StringBuilder block = new StringBuilder();
        for (int i = 0; i < passages.size(); i++) {
            RetrievedPolicy passage = passages.get(i);
            block.append('[').append(i + 1).append("] source=").append(passage.reference())
                    .append(" version=").append(passage.version()).append('\n')
                    .append(passage.text().strip()).append("\n\n");
        }
        return block.toString().strip();
    }
}
