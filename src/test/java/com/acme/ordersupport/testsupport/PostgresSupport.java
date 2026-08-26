package com.acme.ordersupport.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Postgres for the whole run.
 *
 * <p>Started in a static initialiser rather than with {@code @Container}, which
 * would start and stop a database per test class. Ryuk reaps it when the JVM
 * exits. The image is pgvector's, because a test that stubs out the vector
 * extension is not testing retrieval.
 */
public abstract class PostgresSupport {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ordersupport")
            .withUsername("ordersupport")
            .withPassword("ordersupport");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
