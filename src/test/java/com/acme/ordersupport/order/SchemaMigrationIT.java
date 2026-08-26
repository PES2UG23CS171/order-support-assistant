package com.acme.ordersupport.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ordersupport.testsupport.IntegrationTest;
import com.acme.ordersupport.testsupport.PostgresSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The migrations run, and the schema they produce is the one the code expects.
 * Cheap, and it fails loudly the first time someone hand-edits a table instead of
 * writing a migration.
 */
@IntegrationTest
class SchemaMigrationIT extends PostgresSupport {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void everyMigrationAppliedCleanly() {
        List<String> failed = jdbc.sql(
                        "SELECT version FROM flyway_schema_history WHERE success = false")
                .query(String.class).list();

        assertThat(failed).isEmpty();
        assertThat(jdbc.sql("SELECT version FROM flyway_schema_history ORDER BY installed_rank")
                .query(String.class).list()).contains("1", "2", "900");
    }

    @Test
    void theDomainAndGuardrailTablesBothExist() {
        List<String> tables = jdbc.sql(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
                .query(String.class).list();

        assertThat(tables).contains("orders", "order_items", "shipments", "return_requests", "refunds",
                "pending_actions", "tool_invocations", "guardrail_events", "vector_store");
    }

    @Test
    void theVectorColumnHasTheDimensionTheEmbeddingModelProduces() {
        // A mismatch here surfaces as an opaque insert failure at ingestion time,
        // so it is worth asserting directly.
        String type = jdbc.sql("""
                        SELECT format_type(a.atttypid, a.atttypmod)
                          FROM pg_attribute a
                          JOIN pg_class c ON c.oid = a.attrelid
                         WHERE c.relname = 'vector_store' AND a.attname = 'embedding'
                        """)
                .query(String.class).single();

        assertThat(type).isEqualTo("vector(1536)");
    }

    @Test
    void ordersCarryAnOptimisticLockColumn() {
        assertThat(jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                         WHERE table_name = 'orders'
                        """).query(String.class).list())
                .contains("version");
    }

    @Test
    void theSeedFixturesAreLoadedForTheTestAndLocalProfilesOnly() {
        assertThat(jdbc.sql("SELECT count(*) FROM orders").query(Long.class).single()).isEqualTo(8L);
        assertThat(jdbc.sql("SELECT count(*) FROM customers").query(Long.class).single()).isEqualTo(2L);
    }
}
