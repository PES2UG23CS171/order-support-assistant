package com.acme.ordersupport.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyDocumentLoaderTest {

    private final PolicyDocumentLoader loader = new PolicyDocumentLoader();

    @Test
    void loadsEveryDocumentInTheCorpus() {
        List<PolicyChunk> chunks = loader.load(1200, 150);

        assertThat(chunks).extracting(PolicyChunk::policyId).contains(
                "returns-policy", "refunds-policy", "shipping-delivery-faq", "order-cancellation-policy");
    }

    @Test
    void readsFrontMatterAndSplitsOnSectionHeadings() {
        List<PolicyChunk> returns = loader.load(1200, 150).stream()
                .filter(chunk -> chunk.policyId().equals("returns-policy"))
                .toList();

        assertThat(returns).isNotEmpty();
        assertThat(returns.get(0).title()).isEqualTo("Returns Policy");
        assertThat(returns.get(0).version()).isEqualTo("2026-03-01");
        assertThat(returns).extracting(PolicyChunk::section)
                .contains("Standard return window", "Non-returnable items", "Return shipping costs");
    }

    @Test
    void everyChunkCarriesItsTitleAndSectionInTheEmbeddedText() {
        // The heading has to survive into the chunk text, or a passage retrieved on
        // its own reads as an unattributed fragment.
        assertThat(loader.load(1200, 150))
                .allSatisfy(chunk -> assertThat(chunk.text()).startsWith(chunk.title() + " -- " + chunk.section()));
    }

    @Test
    void chunkIdsAreStableAcrossRunsSoReingestionUpserts() {
        assertThat(loader.load(1200, 150).stream().map(PolicyChunk::id).toList())
                .isEqualTo(loader.load(1200, 150).stream().map(PolicyChunk::id).toList());
    }

    @Test
    void chunkIdsAreUnique() {
        List<String> ids = loader.load(1200, 150).stream().map(PolicyChunk::id).toList();

        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void everyDocumentHashIsConsistentWithinItsOwnChunks() {
        loader.load(1200, 150).stream()
                .collect(java.util.stream.Collectors.groupingBy(PolicyChunk::policyId))
                .forEach((policyId, chunks) -> assertThat(chunks).extracting(PolicyChunk::docHash)
                        .containsOnly(chunks.get(0).docHash()));
    }

    @Test
    void aSectionUnderTheLimitIsLeftWhole() {
        assertThat(PolicyDocumentLoader.splitLongSection("short body", 1200, 150))
                .containsExactly("short body");
    }

    @Test
    void anOversizedSectionIsSplitOnParagraphsWithOverlap() {
        String section = ("Paragraph one is reasonably long and says something about returns.\n\n"
                          + "Paragraph two continues the thought with more detail.\n\n"
                          + "Paragraph three closes it out.");

        List<String> pieces = PolicyDocumentLoader.splitLongSection(section, 80, 20);

        assertThat(pieces).hasSizeGreaterThan(1);
        assertThat(String.join(" ", pieces)).contains("Paragraph three");
    }

    @Test
    void aChangedDocumentProducesADifferentHash() {
        assertThat(PolicyDocumentLoader.sha256Hex("returns policy v1"))
                .isNotEqualTo(PolicyDocumentLoader.sha256Hex("returns policy v2"));
    }
}
