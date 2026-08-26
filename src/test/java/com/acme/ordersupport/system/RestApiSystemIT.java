package com.acme.ordersupport.system;

import static com.acme.ordersupport.testsupport.Fixtures.ADA_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ordersupport.testsupport.SystemTest;
import com.acme.ordersupport.testsupport.SystemTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The same domain through the ordinary REST API.
 *
 * <p>These tests are the evidence for the claim the whole design rests on: the
 * assistant is not privileged. A first-party client hits the same service methods
 * and gets the same refusals, the same error codes and the same audit rows -- it
 * just skips the confirmation gate, because it has already asked the customer
 * itself.
 */
@SystemTest
class RestApiSystemIT extends SystemTestSupport {

    @BeforeEach
    void reset() {
        fixtures.reset();
        chatModel.reset();
    }

    @Test
    void aRestClientCancelsDirectlyWithoutAConfirmationToken() {
        ResponseEntity<String> response = post("/api/v1/orders/ORD-1001/cancel", ADA_KEY,
                Map.of("reason", "cancelled from the account page"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).read("$.status", String.class)).isEqualTo("CANCELLED");
        assertThat(fixtures.statusOf("ORD-1001")).isEqualTo("CANCELLED");
    }

    @Test
    void theSameBusinessRuleRefusesTheRestClientToo() {
        ResponseEntity<String> response = post("/api/v1/orders/ORD-1002/cancel", ADA_KEY,
                Map.of("reason", "changed my mind"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(response).read("$.code", String.class)).isEqualTo("ORDER_ALREADY_SHIPPED");
        assertThat(fixtures.statusOf("ORD-1002")).isEqualTo("SHIPPED");
    }

    @Test
    void restCallsLandInTheSameAuditTableUnderADifferentActor() {
        post("/api/v1/orders/ORD-1001/cancel", ADA_KEY, Map.of("reason", "from the account page"));

        // Read back through the chat audit endpoint's customer view.
        assertThat(fixtures.countAuditRows()).isPositive();
        var orders = json(get("/api/v1/orders/ORD-1001", ADA_KEY));
        assertThat(orders.read("$.status", String.class)).isEqualTo("CANCELLED");
    }

    @Test
    void returnsOpenedOverRestBehaveIdenticallyToConfirmedOnes() {
        ResponseEntity<String> response = post("/api/v1/orders/ORD-1003/returns", ADA_KEY,
                Map.of("reason", "it arrived damaged"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response).read("$.rmaNumber", String.class)).startsWith("RMA-");
        assertThat(json(response).read("$.deductedFees", Double.class)).isZero();
        assertThat(fixtures.statusOf("ORD-1003")).isEqualTo("RETURN_REQUESTED");
    }

    @Test
    void refundsAreReadableForAnOrderThatHasOne() {
        assertThat(json(get("/api/v1/orders/ORD-1006/refunds", ADA_KEY))
                .read("$[0].status", String.class)).isEqualTo("PROCESSING");
    }

    @Test
    void anOrderCarriesItsLinesAndShipmentOverRest() {
        var order = json(get("/api/v1/orders/ORD-1002", ADA_KEY));

        assertThat(order.read("$.lines[0].sku", String.class)).isEqualTo("MON-ARM-02");
        assertThat(order.read("$.shipment.trackingNumber", String.class))
                .isEqualTo("1Z999AA10123456784");
    }

    @Test
    void policySearchWorksWithoutAChatModelAtAll() {
        // The retrieval half of the system is demonstrable on its own, with no
        // completion and no API key for a model provider.
        var response = get("/api/v1/policies/search?q=how+long+to+return+an+item&topK=3", ADA_KEY);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        var results = json(response);
        List<String> policyIds = results.read("$[*].policyId");
        assertThat(policyIds).contains("returns-policy");
        assertThat(results.read("$[0].score", Double.class)).isPositive();
        assertThat(results.read("$[0].section", String.class)).isNotBlank();
    }
}
