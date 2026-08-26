package com.acme.ordersupport.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingRequest;

class HashingEmbeddingModelTest {

    private final HashingEmbeddingModel model = new HashingEmbeddingModel();

    @Test
    void producesTheDimensionThePgvectorColumnDeclares() {
        assertThat(model.dimensions()).isEqualTo(1536);
        assertThat(model.embed("anything")).hasSize(1536);
    }

    @Test
    void isDeterministicAcrossCalls() {
        // The point of this model: the same corpus embeds identically on every CI
        // run, so a retrieval assertion cannot flake.
        assertThat(model.embed("how long do I have to return an item"))
                .isEqualTo(model.embed("how long do I have to return an item"));
    }

    @Test
    void vectorsAreUnitLengthSoDotProductIsCosine() {
        float[] vector = model.embed("returns policy standard window thirty days");

        double norm = 0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void textSharingVocabularyScoresHigherThanTextThatDoesNot() {
        float[] query = model.embed("how many days to return a delivered item");
        double onTopic = cosine(query, model.embed(
                "Standard return window. Most items may be returned within 30 days of the delivery date."));
        double offTopic = cosine(query, model.embed(
                "International shipping. Duties and import taxes are collected by the carrier."));

        assertThat(onTopic).isGreaterThan(offTopic);
    }

    @Test
    void stopWordsDoNotDominateShortQueries() {
        // "the of and to" carries no signal; a query made only of stop words should
        // not look similar to an arbitrary document.
        assertThat(cosine(model.embed("the of and to is it"),
                model.embed("Refunds settle in five to ten business days"))).isLessThan(0.2);
    }

    @Test
    void pluralsCollapseOntoTheirSingular() {
        assertThat(cosine(model.embed("refunds"), model.embed("refund"))).isGreaterThan(0.99);
    }

    @Test
    void blankInputYieldsAZeroVectorRatherThanBlowingUp() {
        assertThat(model.embed("")).containsOnly(0f);
        assertThat(model.embed((String) null)).containsOnly(0f);
    }

    @Test
    void batchCallsKeepTheirInputOrder() {
        var response = model.call(new EmbeddingRequest(List.of("returns", "shipping", "refunds"), null));

        assertThat(response.getResults()).hasSize(3);
        assertThat(response.getResults()).extracting("index").containsExactly(0, 1, 2);
        assertThat(response.getResults().get(0).getOutput()).isEqualTo(model.embed("returns"));
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }
}
