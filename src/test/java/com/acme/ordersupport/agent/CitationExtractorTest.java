package com.acme.ordersupport.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ordersupport.agent.model.Citation;
import com.acme.ordersupport.rag.RetrievedPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class CitationExtractorTest {

    private static final List<RetrievedPolicy> PASSAGES = List.of(
            passage("returns-policy", "Standard return window", 0.81),
            passage("refunds-policy", "Processing time", 0.64));

    @Test
    void resolvesMarkersToThePassagesThatWereActuallySupplied() {
        List<Citation> citations = CitationExtractor.citedIn(
                "You have 30 days from delivery [1], and refunds settle in 5 to 10 business days [2].",
                PASSAGES);

        assertThat(citations).extracting(Citation::marker).containsExactly(1, 2);
        assertThat(citations).extracting(Citation::policyId)
                .containsExactly("returns-policy", "refunds-policy");
    }

    @Test
    void dropsMarkersThatPointAtNothing() {
        // A fabricated [7] against two passages is exactly the failure this is here
        // to make visible: it resolves to nothing and never becomes a citation.
        List<Citation> citations = CitationExtractor.citedIn("Per our policy [7], that is fine.", PASSAGES);

        assertThat(citations).isEmpty();
    }

    @Test
    void reportsEachPassageOnceEvenWhenCitedRepeatedly() {
        List<Citation> citations = CitationExtractor.citedIn(
                "The window is 30 days [1]. Furniture is different [1]. Refunds take a week [2].", PASSAGES);

        assertThat(citations).hasSize(2);
    }

    @Test
    void anUngroundedAnswerProducesNoCitations() {
        assertThat(CitationExtractor.citedIn("Sure, I'll sort that out for you.", PASSAGES)).isEmpty();
    }

    @Test
    void everyRetrievedPassageIsReportedSeparatelyFromWhatWasCited() {
        assertThat(CitationExtractor.asCitations(PASSAGES))
                .extracting(Citation::marker).containsExactly(1, 2);
    }

    @Test
    void excerptsAreTruncatedRatherThanDumpingTheWholeChunk() {
        var longPassage = new RetrievedPolicy("id", "returns-policy", "Returns Policy", "Long section",
                "2026-03-01", "word ".repeat(400), 0.5);

        String excerpt = CitationExtractor.asCitations(List.of(longPassage)).get(0).excerpt();

        assertThat(excerpt).hasSize(320).endsWith("...");
    }

    private static RetrievedPolicy passage(String policyId, String section, double score) {
        return new RetrievedPolicy("doc-" + policyId, policyId, policyId, section, "2026-03-01",
                policyId + " -- " + section + "\n\nSome policy text.", score);
    }
}
