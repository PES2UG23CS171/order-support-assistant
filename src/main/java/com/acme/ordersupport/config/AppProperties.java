package com.acme.ordersupport.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything tunable about the assistant, in one place. Nested records use
 * constructor binding, so a missing key falls back to the default written here
 * rather than to null.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Ai ai, Rag rag, Agent agent, Guardrail guardrail) {

    public AppProperties {
        ai = ai != null ? ai : new Ai(null);
        rag = rag != null ? rag : new Rag(null, null, null, null, null);
        agent = agent != null ? agent : new Agent(null, null);
        guardrail = guardrail != null ? guardrail : new Guardrail(null, null);
    }

    /**
     * {@code openai} uses the hosted embedding model; {@code offline} swaps in a
     * deterministic local embedding model so retrieval can be exercised in CI and
     * in demos without an API key or a network call.
     */
    public record Ai(String mode) {
        public static final String OFFLINE = "offline";

        public Ai {
            mode = mode != null ? mode : "openai";
        }

        public boolean offline() {
            return OFFLINE.equalsIgnoreCase(mode);
        }
    }

    public record Rag(Integer topK, Double similarityThreshold, Boolean ingestOnStartup,
                      Integer maxChunkChars, Integer chunkOverlapChars) {
        public Rag {
            topK = topK != null ? topK : 4;
            similarityThreshold = similarityThreshold != null ? similarityThreshold : 0.35d;
            ingestOnStartup = ingestOnStartup != null ? ingestOnStartup : Boolean.TRUE;
            maxChunkChars = maxChunkChars != null ? maxChunkChars : 1200;
            chunkOverlapChars = chunkOverlapChars != null ? chunkOverlapChars : 150;
        }
    }

    public record Agent(Duration confirmationTtl, Integer maxHistoryMessages) {
        public Agent {
            confirmationTtl = confirmationTtl != null ? confirmationTtl : Duration.ofMinutes(10);
            maxHistoryMessages = maxHistoryMessages != null ? maxHistoryMessages : 12;
        }
    }

    public record Guardrail(Boolean scopeCheckEnabled, Boolean injectionCheckEnabled) {
        public Guardrail {
            scopeCheckEnabled = scopeCheckEnabled != null ? scopeCheckEnabled : Boolean.TRUE;
            injectionCheckEnabled = injectionCheckEnabled != null ? injectionCheckEnabled : Boolean.TRUE;
        }
    }
}
