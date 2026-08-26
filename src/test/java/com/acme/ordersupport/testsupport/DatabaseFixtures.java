package com.acme.ordersupport.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Puts the demo data back the way it started.
 *
 * <p>Tests mutate real rows -- that is the point of an integration suite -- so
 * every test that does gets a clean slate first. Truncating and replaying the seed
 * script is faster than a Flyway clean and migrate, and it reuses the exact file
 * that ships with the application, so a fixture cannot drift from the demo data.
 */
public class DatabaseFixtures {

    private static final String TRUNCATE = """
            TRUNCATE tool_invocations, guardrail_events, chat_messages, pending_actions, conversations,
                     refunds, return_requests, shipments, order_items, orders, customers
            RESTART IDENTITY CASCADE
            """;

    private final JdbcClient jdbc;

    public DatabaseFixtures(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void reset() {
        jdbc.sql(TRUNCATE).update();
        for (String statement : splitStatements(read("db/seed/V900__demo_data.sql"))) {
            jdbc.sql(statement).update();
        }
    }

    public long countOrdersWithStatus(String status) {
        Long count = jdbc.sql("SELECT count(*) FROM orders WHERE status = :status")
                .param("status", status).query(Long.class).single();
        return count == null ? 0 : count;
    }

    public String statusOf(String orderNumber) {
        return jdbc.sql("SELECT status FROM orders WHERE order_number = :orderNumber")
                .param("orderNumber", orderNumber).query(String.class).single();
    }

    public long countRefundsFor(String orderNumber) {
        Long count = jdbc.sql("""
                        SELECT count(*) FROM refunds f JOIN orders o ON o.id = f.order_id
                         WHERE o.order_number = :orderNumber
                        """)
                .param("orderNumber", orderNumber).query(Long.class).single();
        return count == null ? 0 : count;
    }

    public long countAuditRows() {
        Long count = jdbc.sql("SELECT count(*) FROM tool_invocations").query(Long.class).single();
        return count == null ? 0 : count;
    }

    private static String[] splitStatements(String script) {
        String withoutComments = script.replaceAll("(?m)^\\s*--.*$", "")
                .replaceAll("(?m)\\s+--[^\\n']*$", "");
        return java.util.Arrays.stream(withoutComments.split(";"))
                .map(String::strip)
                .filter(statement -> !statement.isBlank())
                .toArray(String[]::new);
    }

    private static String read(String location) {
        try (var stream = new ClassPathResource(location).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new UncheckedIOException("Unable to read " + location, ex);
        }
    }
}
