package com.acme.ordersupport.system;

import static com.acme.ordersupport.testsupport.Fixtures.ADA_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ordersupport.testsupport.PostgresSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The application with no model provider configured at all.
 *
 * <p>This is the configuration a container smoke test and a keyless demo run in,
 * and it used to be fatal: the chat client was a hard dependency of the agent, so
 * a missing API key stopped the process booting and took order lookups and policy
 * search down with it. One unconfigured optional dependency should cost one
 * endpoint, not the service.
 *
 * <p>Deliberately does not import the scripted model, so there is genuinely no
 * {@code ChatModel} bean in this context.
 */
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
@ActiveProfiles("test")
class NoChatModelSystemIT extends PostgresSupport {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void theApplicationStartsAndReportsHealthy() {
        assertThat(get("/actuator/health").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ordersAndRefundsAreUnaffected() {
        assertThat(get("/api/v1/orders").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/orders/ORD-1002").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/orders/ORD-1006/refunds").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void retrievalStillWorksBecauseItNeverNeededAChatModel() {
        ResponseEntity<String> response =
                get("/api/v1/policies/search?q=how+long+to+return+an+item&topK=3");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> policyIds = com.jayway.jsonpath.JsonPath.parse(response.getBody())
                .read("$[*].policyId");
        assertThat(policyIds).contains("returns-policy");
    }

    @Test
    void onlyTheChatEndpointDegrades() {
        ResponseEntity<String> response = rest.exchange(
                url("/api/v1/chat"), HttpMethod.POST,
                new HttpEntity<>(Map.of("message", "where is my order ORD-1002"), headers()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        var problem = com.jayway.jsonpath.JsonPath.parse(response.getBody());
        assertThat(problem.read("$.code", String.class)).isEqualTo("ASSISTANT_NOT_CONFIGURED");
        assertThat(problem.read("$.detail", String.class)).contains("policy search are unaffected");
    }

    private ResponseEntity<String> get(String path) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers()), String.class);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", ADA_KEY);
        return headers;
    }
}
