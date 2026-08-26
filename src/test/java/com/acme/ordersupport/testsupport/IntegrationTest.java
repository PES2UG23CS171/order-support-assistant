package com.acme.ordersupport.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Wiring shared by the integration suite: real database, real everything, with the
 * hosted models switched off.
 *
 * <p>{@code spring.ai.model.*=none} disables the OpenAI auto-configurations
 * outright rather than feeding them a fake key, so a test can never reach the
 * network by accident. Embeddings come from the deterministic local model.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("integration")
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.ai.mode=offline",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
        "app.rag.ingest-on-startup=true"
})
@Import(TestAiConfig.class)
@ActiveProfiles("test")
public @interface IntegrationTest {
}
