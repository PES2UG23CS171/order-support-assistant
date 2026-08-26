package com.acme.ordersupport.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.acme.ordersupport.config.AppProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

@ExtendWith(MockitoExtension.class)
class PolicyRetrieverTest {

    @Mock
    private VectorStore vectorStore;

    private PolicyRetriever retriever() {
        return new PolicyRetriever(vectorStore, new AppProperties(null, null, null, null));
    }

    @Test
    void mapsStoreMetadataOntoTheCitationShape() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                Document.builder().id("doc-1").text("Returns Policy -- Standard return window\n\n30 days.")
                        .metadata(Map.of("policy_id", "returns-policy", "title", "Returns Policy",
                                "section", "Standard return window", "version", "2026-03-01"))
                        .score(0.77).build()));

        List<RetrievedPolicy> passages = retriever().retrieve("how long to return");

        assertThat(passages).singleElement().satisfies(passage -> {
            assertThat(passage.policyId()).isEqualTo("returns-policy");
            assertThat(passage.section()).isEqualTo("Standard return window");
            assertThat(passage.score()).isEqualTo(0.77);
            assertThat(passage.reference()).isEqualTo("returns-policy#Standard return window");
        });
    }

    @Test
    void survivesDocumentsWithMetadataMissing() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(Document.builder().id("doc-2").text("orphan chunk").build()));

        assertThat(retriever().retrieve("anything")).singleElement()
                .satisfies(passage -> assertThat(passage.policyId()).isEqualTo("unknown"));
    }

    @Test
    void aBlankQueryNeverReachesTheStore() {
        assertThat(retriever().retrieve("  ")).isEmpty();
        verifyNoInteractions(vectorStore);
    }

    @Test
    void contextBlockNumbersPassagesFromOneAndLabelsTheirSource() {
        String block = PolicyRetriever.asContextBlock(List.of(
                new RetrievedPolicy("d1", "returns-policy", "Returns Policy", "Standard return window",
                        "2026-03-01", "Most items may be returned within 30 days.", 0.8),
                new RetrievedPolicy("d2", "refunds-policy", "Refunds Policy", "Processing time",
                        "2026-03-01", "Refunds settle in 5 to 10 business days.", 0.6)));

        assertThat(block)
                .contains("[1] source=returns-policy#Standard return window version=2026-03-01")
                .contains("[2] source=refunds-policy#Processing time")
                .contains("Most items may be returned within 30 days.");
    }

    @Test
    void anEmptyContextBlockSaysSoRatherThanBeingBlank() {
        // A blank block would read to the model as "no constraint here"; the
        // explicit note keeps the ungrounded case visible in the prompt.
        assertThat(PolicyRetriever.asContextBlock(List.of()))
                .isEqualTo("(no policy passages matched this question)");
    }
}
