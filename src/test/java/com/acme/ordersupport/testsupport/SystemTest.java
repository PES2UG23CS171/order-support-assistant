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
 * The system suite: the application on a real port, exercised over HTTP through
 * its own security filter chain, against a real database.
 *
 * <p>Nothing is injected into the test but the port and a REST client -- assertions
 * go through the API a customer would use, and the only shortcut is reading the
 * database afterwards to check that what the API claimed happened, happened.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("system")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "app.ai.mode=offline",
                "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
                "app.rag.ingest-on-startup=true"
        })
@Import(TestAiConfig.class)
@ActiveProfiles("test")
public @interface SystemTest {
}
