package com.acme.ordersupport.testsupport;

import com.acme.ordersupport.agent.SupportTools;
import java.util.List;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the hosted chat model with a scripted one.
 *
 * <p>Everything else stays real: real Postgres, real pgvector, real Flyway, real
 * Spring AI tool calling, real security filter chain. The only seam is the token
 * generator, which is the one component whose output a test cannot meaningfully
 * assert on anyway.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestAiConfig {

    @Bean
    @Primary
    ScriptedChatModel scriptedChatModel(ToolCallingManager toolCallingManager, SupportTools supportTools) {
        return new ScriptedChatModel(toolCallingManager, List.of(ToolCallbacks.from(supportTools)));
    }

    @Bean
    DatabaseFixtures databaseFixtures(org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        return new DatabaseFixtures(jdbc);
    }
}
