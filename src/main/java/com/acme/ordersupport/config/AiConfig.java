package com.acme.ordersupport.config;

import com.acme.ordersupport.rag.HashingEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /**
     * Swaps in the deterministic local embedding model.
     *
     * <p>Marked primary so it wins over any provider model the starters
     * auto-configure, which keeps the switch to one property instead of a
     * different set of excluded auto-configurations per environment.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.ai.mode", havingValue = "offline")
    EmbeddingModel offlineEmbeddingModel() {
        log.info("app.ai.mode=offline: using the deterministic local embedding model. "
                 + "Retrieval is exact and free; it matches on shared vocabulary rather than meaning.");
        return new HashingEmbeddingModel();
    }
}
