package com.acme.ordersupport.rag;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ordersupport.testsupport.IntegrationTest;
import com.acme.ordersupport.testsupport.PostgresSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Retrieval against real pgvector: real embeddings from the deterministic model,
 * a real HNSW cosine index, real ranking. Nothing here is stubbed, which is why
 * these assertions are worth making.
 */
@IntegrationTest
class PolicyRetrievalIT extends PostgresSupport {

    @Autowired
    private PolicyIngestionService ingestion;

    @Autowired
    private PolicyRetriever retriever;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void theWholeCorpusIsInTheStoreAfterStartup() {
        assertThat(ingestion.ingestedPolicyIds()).contains(
                "returns-policy", "refunds-policy", "shipping-delivery-faq", "order-cancellation-policy");
        assertThat(jdbc.sql("SELECT count(*) FROM vector_store WHERE embedding IS NOT NULL")
                .query(Long.class).single()).isPositive();
    }

    @Test
    void reingestingAnUnchangedCorpusEmbedsNothing() {
        // Embedding is the expensive call in RAG. A restart must not repeat it.
        assertThat(ingestion.ingest()).isZero();
    }

    @Test
    void reingestingDoesNotDuplicateChunks() {
        long before = chunkCount();

        ingestion.ingest();

        assertThat(chunkCount()).isEqualTo(before);
    }

    @Test
    void aReturnWindowQuestionRetrievesTheReturnsPolicyFirst() {
        List<RetrievedPolicy> passages = retriever.retrieve(
                "how many days do I have to return a delivered item", 4, 0.0);

        assertThat(passages).isNotEmpty();
        assertThat(passages.get(0).policyId()).isEqualTo("returns-policy");
        assertThat(passages.get(0).section()).isEqualTo("Standard return window");
    }

    @Test
    void arefundTimingQuestionRetrievesTheRefundsPolicy() {
        List<RetrievedPolicy> passages = retriever.retrieve(
                "how long until my refund money settles back on my card", 4, 0.0);

        assertThat(passages).extracting(RetrievedPolicy::policyId).contains("refunds-policy");
    }

    @Test
    void aLostParcelQuestionRetrievesTheShippingFaq() {
        List<RetrievedPolicy> passages = retriever.retrieve(
                "tracking says delivered but the parcel is lost and never arrived", 4, 0.0);

        assertThat(passages).extracting(RetrievedPolicy::policyId).contains("shipping-delivery-faq");
    }

    @Test
    void scoresAreOrderedAndCarryThroughToTheCitation() {
        List<RetrievedPolicy> passages = retriever.retrieve("cancel my order before it ships", 4, 0.0);

        assertThat(passages).isSortedAccordingTo(
                (a, b) -> Double.compare(b.score(), a.score()));
        assertThat(passages).allSatisfy(passage -> {
            assertThat(passage.score()).isBetween(0.0, 1.0);
            assertThat(passage.reference()).contains("#");
            assertThat(passage.version()).isEqualTo("2026-03-01");
        });
    }

    @Test
    void theSimilarityThresholdActuallyExcludesWeakMatches() {
        assertThat(retriever.retrieve("aardvark xylophone quantum", 4, 0.9)).isEmpty();
    }

    @Test
    void everyStoredChunkCarriesTheMetadataCitationsAreBuiltFrom() {
        List<String> missing = jdbc.sql("""
                        SELECT id::text FROM vector_store
                         WHERE metadata ->> 'policy_id' IS NULL
                            OR metadata ->> 'section' IS NULL
                            OR metadata ->> 'version' IS NULL
                            OR metadata ->> 'doc_hash' IS NULL
                        """).query(String.class).list();

        assertThat(missing).isEmpty();
    }

    private long chunkCount() {
        Long count = jdbc.sql("SELECT count(*) FROM vector_store").query(Long.class).single();
        return count == null ? 0 : count;
    }
}
