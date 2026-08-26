package com.acme.ordersupport.order.repo;

import com.acme.ordersupport.config.CustomerPrincipal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {

    private final JdbcClient jdbc;

    public CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Looks a caller up by the SHA-256 of their API key; the raw key is never stored. */
    public Optional<CustomerPrincipal> findByApiKeyHash(String apiKeyHash) {
        return jdbc.sql("SELECT id, email, full_name FROM customers WHERE api_key_hash = :hash")
                .param("hash", apiKeyHash)
                .query((rs, rowNum) -> new CustomerPrincipal(
                        rs.getObject("id", java.util.UUID.class),
                        rs.getString("email"),
                        rs.getString("full_name")))
                .optional();
    }
}
