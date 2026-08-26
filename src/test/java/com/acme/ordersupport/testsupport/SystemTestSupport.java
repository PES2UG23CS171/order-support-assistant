package com.acme.ordersupport.testsupport;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The system suite talks to the application the way a client does: over HTTP, with
 * an API key, through the real filter chain. Nothing internal is injected except
 * the scripted model and a database handle used only to check afterwards that what
 * the API said happened actually happened.
 */
public abstract class SystemTestSupport extends PostgresSupport {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ScriptedChatModel chatModel;

    @Autowired
    protected DatabaseFixtures fixtures;

    protected ResponseEntity<String> post(String path, String apiKey, Object body) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers(apiKey)),
                String.class);
    }

    protected ResponseEntity<String> get(String path, String apiKey) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers(apiKey)), String.class);
    }

    /** Sends a chat turn and returns the parsed response body. */
    protected DocumentContext chat(String apiKey, String message) {
        return json(post("/api/v1/chat", apiKey, Map.of("message", message)));
    }

    protected DocumentContext chat(String apiKey, String conversationId, String message) {
        return json(post("/api/v1/chat", apiKey,
                Map.of("conversationId", conversationId, "message", message)));
    }

    protected static DocumentContext json(ResponseEntity<String> response) {
        return JsonPath.parse(response.getBody());
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static HttpHeaders headers(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.set("X-API-Key", apiKey);
        }
        return headers;
    }
}
