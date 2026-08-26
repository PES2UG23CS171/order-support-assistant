package com.acme.ordersupport.system;

import static com.acme.ordersupport.testsupport.Fixtures.ADA_KEY;
import static com.acme.ordersupport.testsupport.Fixtures.BLAKE_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ordersupport.testsupport.SystemTest;
import com.acme.ordersupport.testsupport.SystemTestSupport;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SystemTest
class SecuritySystemIT extends SystemTestSupport {

    @BeforeEach
    void reset() {
        fixtures.reset();
        chatModel.reset();
    }

    @Test
    void noApiKeyIsUnauthorised() {
        ResponseEntity<String> response = post("/api/v1/chat", null, Map.of("message", "hello"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(json(response).read("$.detail", String.class)).contains("X-API-Key");
    }

    @Test
    void anUnknownApiKeyIsUnauthorised() {
        assertThat(post("/api/v1/chat", "osa_not_a_real_key", Map.of("message", "hello")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void everyApiRouteRequiresAKey() {
        assertThat(get("/api/v1/orders", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/orders/ORD-1001", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/policies/search?q=returns", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void healthIsOpenSoAContainerCanProbeIt() {
        ResponseEntity<String> response = get("/actuator/health", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).read("$.status", String.class)).isEqualTo("UP");
    }

    @Test
    void oneCustomerCannotReadAnothersOrderOverRest() {
        assertThat(get("/api/v1/orders/ORD-2001", ADA_KEY).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/orders/ORD-2001", BLAKE_KEY).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aNonexistentOrderAndSomeoneElsesOrderAreIndistinguishable() {
        // Same status, same code, same shape. The only difference is the order
        // number echoed back, which the caller supplied.
        var missing = json(get("/api/v1/orders/ORD-9999", ADA_KEY));
        var notMine = json(get("/api/v1/orders/ORD-2001", ADA_KEY));

        assertThat(missing.read("$.code", String.class)).isEqualTo(notMine.read("$.code", String.class));
        assertThat(missing.read("$.title", String.class)).isEqualTo(notMine.read("$.title", String.class));
        assertThat(missing.read("$.detail", String.class).replace("ORD-9999", "X"))
                .isEqualTo(notMine.read("$.detail", String.class).replace("ORD-2001", "X"));
    }

    @Test
    void listingOrdersIsScopedToTheKeyThatAskedForIt() {
        java.util.List<String> orderNumbers = json(get("/api/v1/orders", BLAKE_KEY))
                .read("$[*].orderNumber");
        assertThat(orderNumbers).containsExactly("ORD-2001");
    }
}
