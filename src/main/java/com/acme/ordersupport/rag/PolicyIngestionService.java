package com.acme.ordersupport.rag;

import com.acme.ordersupport.config.AppProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Loads the policy corpus into pgvector.
 *
 * <p>Embedding is the expensive part of RAG and the part people forget is
 * expensive, so this checks first: each document carries a hash of its source
 * text, and a document whose hash is already present in the store at the expected
 * chunk count is skipped entirely. A restart costs nothing. An edit to one
 * document re-embeds that document alone.
 */
@Service
public class PolicyIngestionService {

    private static final Logger log = LoggerFactory.getLogger(PolicyIngestionService.class);

    private final PolicyDocumentLoader loader;
    private final VectorStore vectorStore;
    private final JdbcClient jdbc;
    private final AppProperties properties;

    public PolicyIngestionService(PolicyDocumentLoader loader, VectorStore vectorStore, JdbcClient jdbc,
                                  AppProperties properties) {
        this.loader = loader;
        this.vectorStore = vectorStore;
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ingestOnStartup() {
        if (!properties.rag().ingestOnStartup()) {
            log.info("Policy ingestion on startup is disabled (app.rag.ingest-on-startup=false)");
            return;
        }
        int ingested = ingest();
        log.info("Policy corpus ready: {} chunk(s) embedded this run", ingested);
    }

    /** @return the number of chunks actually embedded (0 when everything was current) */
    public int ingest() {
        List<PolicyChunk> chunks = loader.load(
                properties.rag().maxChunkChars(), properties.rag().chunkOverlapChars());

        Map<String, List<PolicyChunk>> byPolicy = chunks.stream()
                .collect(Collectors.groupingBy(PolicyChunk::policyId));

        int embedded = 0;
        for (var entry : byPolicy.entrySet()) {
            List<PolicyChunk> policyChunks = entry.getValue();
            String docHash = policyChunks.get(0).docHash();

            if (upToDate(entry.getKey(), docHash, policyChunks.size())) {
                log.debug("Policy {} is unchanged; skipping re-embedding", entry.getKey());
                continue;
            }

            // Drop chunks from an older revision first: an edit that removes a
            // section would otherwise leave that section retrievable forever.
            removeStaleRevisions(entry.getKey(), docHash);

            vectorStore.add(policyChunks.stream().map(PolicyIngestionService::toDocument).toList());
            embedded += policyChunks.size();
            log.info("Embedded {} chunk(s) for policy {} (revision {})",
                    policyChunks.size(), entry.getKey(), docHash.substring(0, 8));
        }
        return embedded;
    }

    private boolean upToDate(String policyId, String docHash, int expectedChunks) {
        Integer count = jdbc.sql("""
                        SELECT count(*) FROM vector_store
                         WHERE metadata ->> 'policy_id' = :policyId
                           AND metadata ->> 'doc_hash' = :docHash
                        """)
                .param("policyId", policyId)
                .param("docHash", docHash)
                .query(Integer.class)
                .single();
        return count != null && count == expectedChunks;
    }

    private void removeStaleRevisions(String policyId, String currentDocHash) {
        List<String> stale = jdbc.sql("""
                        SELECT id::text FROM vector_store
                         WHERE metadata ->> 'policy_id' = :policyId
                           AND metadata ->> 'doc_hash' <> :docHash
                        """)
                .param("policyId", policyId)
                .param("docHash", currentDocHash)
                .query(String.class)
                .list();
        if (!stale.isEmpty()) {
            vectorStore.delete(stale);
            log.info("Removed {} chunk(s) from a previous revision of policy {}", stale.size(), policyId);
        }
    }

    static Document toDocument(PolicyChunk chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("policy_id", chunk.policyId());
        metadata.put("title", chunk.title());
        metadata.put("section", chunk.section());
        metadata.put("version", chunk.version());
        metadata.put("source", chunk.source());
        metadata.put("chunk_index", chunk.chunkIndex());
        metadata.put("doc_hash", chunk.docHash());
        return Document.builder().id(chunk.id()).text(chunk.text()).metadata(metadata).build();
    }

    /** Policy ids currently represented in the store; used by the health surface and tests. */
    public Set<String> ingestedPolicyIds() {
        return Set.copyOf(jdbc.sql("SELECT DISTINCT metadata ->> 'policy_id' FROM vector_store")
                .query(String.class)
                .list());
    }
}
