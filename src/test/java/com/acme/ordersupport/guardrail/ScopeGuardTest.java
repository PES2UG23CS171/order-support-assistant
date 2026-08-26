package com.acme.ordersupport.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ordersupport.config.AppProperties;
import com.acme.ordersupport.rag.RetrievedPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScopeGuardTest {

    private final ScopeGuard guard = new ScopeGuard(new AppProperties(null, null, null, null));

    private static final RetrievedPolicy MATCH = new RetrievedPolicy("doc-1", "returns-policy",
            "Returns Policy", "Standard return window", "2026-03-01", "Most items may be returned...", 0.72);

    @ParameterizedTest
    @ValueSource(strings = {
            "What's the weather like in Austin tomorrow?",
            "Write me a Python script to scrape a website",
            "Can you translate this into French?",
            "What should I do about my back pain, the symptoms started yesterday",
            "Give me a recipe for banana bread",
            "Write me a poem about autumn",
            "Are you ChatGPT?",
            "Which stock should I buy this week?"})
    void blocksThingsThatAreNotThisAssistantsProblem(String message) {
        GuardDecision decision = guard.assess(message, List.of());

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.guardrail()).isEqualTo(ScopeGuard.NAME);
    }

    @Test
    void anOffTopicPatternWinsEvenWhenADomainWordIsPresent() {
        // "return" is a domain word; "what's the return on this stock" is not a
        // question about returns.
        assertThat(guard.assess("What return should I expect if I buy that stock?", List.of()).blocked())
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Where is ORD-1002?",
            "rma-12345678 what's happening with it",
            "My parcel never turned up",
            "I'd like a refund",
            "when will it arrive",
            "the item is broken"})
    void allowsAnythingWithAnOrderReferenceOrDomainVocabulary(String message) {
        assertThat(guard.assess(message, List.of()).blocked()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"hi", "Hello there", "thanks!", "good morning", "ok", "yes please"})
    void letsOrdinaryConversationalTurnsThrough(String message) {
        assertThat(guard.assess(message, List.of()).blocked()).isFalse();
    }

    @Test
    void aStrongPolicyMatchIsItselfEvidenceTheQuestionIsInScope() {
        // No order number and no word from the lexicon, but retrieval found the
        // returns policy -- so it is a policy question phrased unusually.
        GuardDecision decision = guard.assess("how many days do I get to send it back", List.of(MATCH));

        assertThat(decision.blocked()).isFalse();
    }

    @Test
    void withNoSignalAtAllItRefusesRatherThanGuessing() {
        assertThat(guard.assess("who won the world cup in 1998", List.of()).blocked()).isTrue();
    }

    @Test
    void canBeTurnedOffByConfiguration() {
        var disabled = new ScopeGuard(new AppProperties(null, null, null,
                new AppProperties.Guardrail(false, true)));

        assertThat(disabled.assess("what's the weather", List.of()).blocked()).isFalse();
    }
}
