package com.acme.ordersupport.system;

import static com.acme.ordersupport.testsupport.Fixtures.ADA_KEY;
import static com.acme.ordersupport.testsupport.Fixtures.BLAKE_KEY;
import static com.acme.ordersupport.testsupport.ScriptedChatModel.reply;
import static com.acme.ordersupport.testsupport.ScriptedChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

import com.acme.ordersupport.testsupport.SystemTest;
import com.acme.ordersupport.testsupport.SystemTestSupport;
import com.jayway.jsonpath.DocumentContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The guardrail demo, as an executable specification.
 *
 * <p>Every test here answers the same question in a different way: what does it
 * take to actually change an order? The answer is always "a second, authenticated
 * HTTP request from the customer who owns it", and the model never has one.
 */
@SystemTest
class ConfirmationSystemIT extends SystemTestSupport {

    @BeforeEach
    void reset() {
        fixtures.reset();
        chatModel.reset();
    }

    /** Runs a turn where the model tries to cancel ORD-1001, and returns the response. */
    private DocumentContext askToCancel() {
        chatModel.script(
                toolCall("cancelOrder", "{\"orderNumber\":\"ORD-1001\",\"reason\":\"ordered by mistake\"}"),
                reply("I've prepared the cancellation of ORD-1001 -- confirm and I'll put it through."));
        return chat(ADA_KEY, "Please cancel order ORD-1001, I ordered it by mistake");
    }

    @Test
    void theModelCallingCancelOrderDoesNotCancelTheOrder() {
        DocumentContext response = askToCancel();

        assertThat(response.read("$.pendingAction.confirmationToken", String.class)).isNotBlank();
        assertThat(response.read("$.pendingAction.action", String.class)).isEqualTo("CANCEL_ORDER");
        assertThat(response.read("$.pendingAction.orderNumber", String.class)).isEqualTo("ORD-1001");
        assertThat(response.read("$.pendingAction.summary", String.class)).contains("cannot be undone");
        assertThat(response.read("$.toolCalls[0].outcome", String.class)).isEqualTo("CONFIRMATION_REQUIRED");

        // The point of the whole design.
        assertThat(fixtures.statusOf("ORD-1001")).isEqualTo("PROCESSING");
        assertThat(fixtures.countRefundsFor("ORD-1001")).isZero();
    }

    @Test
    void confirmingTheTokenIsWhatActuallyCancelsIt() {
        String token = askToCancel().read("$.pendingAction.confirmationToken");

        ResponseEntity<String> confirmation = post("/api/v1/chat/confirmations/" + token, ADA_KEY,
                Map.of("decision", "APPROVE"));

        assertThat(confirmation.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(confirmation).read("$.status", String.class)).isEqualTo("EXECUTED");
        assertThat(json(confirmation).read("$.message", String.class)).contains("cancelled");
        assertThat(fixtures.statusOf("ORD-1001")).isEqualTo("CANCELLED");
        assertThat(fixtures.countRefundsFor("ORD-1001")).isEqualTo(1);
    }

    @Test
    void rejectingLeavesTheOrderExactlyWhereItWas() {
        String token = askToCancel().read("$.pendingAction.confirmationToken");

        ResponseEntity<String> confirmation = post("/api/v1/chat/confirmations/" + token, ADA_KEY,
                Map.of("decision", "REJECT"));

        assertThat(json(confirmation).read("$.status", String.class)).isEqualTo("REJECTED");
        assertThat(fixtures.statusOf("ORD-1001")).isEqualTo("PROCESSING");
    }

    @Test
    void aTokenCannotBeReplayed() {
        String token = askToCancel().read("$.pendingAction.confirmationToken");
        post("/api/v1/chat/confirmations/" + token, ADA_KEY, Map.of("decision", "APPROVE"));

        ResponseEntity<String> replay = post("/api/v1/chat/confirmations/" + token, ADA_KEY,
                Map.of("decision", "APPROVE"));

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(replay).read("$.code", String.class)).isEqualTo("CONFIRMATION_ALREADY_DECIDED");
        assertThat(fixtures.countRefundsFor("ORD-1001")).isEqualTo(1);
    }

    @Test
    void anotherCustomerHoldingTheTokenCannotRedeemIt() {
        String token = askToCancel().read("$.pendingAction.confirmationToken");

        ResponseEntity<String> stolen = post("/api/v1/chat/confirmations/" + token, BLAKE_KEY,
                Map.of("decision", "APPROVE"));

        assertThat(stolen.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fixtures.statusOf("ORD-1001")).isEqualTo("PROCESSING");
    }

    @Test
    void anUnknownTokenIsNotFound() {
        ResponseEntity<String> response = post(
                "/api/v1/chat/confirmations/1e7c6a4e-0000-4000-8000-000000000000", ADA_KEY,
                Map.of("decision", "APPROVE"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aReturnFollowsTheSameTwoStepPath() {
        chatModel.script(
                toolCall("initiateReturn",
                        "{\"orderNumber\":\"ORD-1003\",\"reason\":\"it arrived damaged\"}"),
                reply("I've prepared a return for ORD-1003 -- confirm and I'll send the label."));

        DocumentContext response = chat(ADA_KEY, "The headphones on ORD-1003 arrived damaged");
        assertThat(fixtures.statusOf("ORD-1003")).isEqualTo("DELIVERED");

        String token = response.read("$.pendingAction.confirmationToken");
        ResponseEntity<String> confirmation = post("/api/v1/chat/confirmations/" + token, ADA_KEY,
                Map.of("decision", "APPROVE"));

        assertThat(json(confirmation).read("$.result.rmaNumber", String.class)).startsWith("RMA-");
        assertThat(fixtures.statusOf("ORD-1003")).isEqualTo("RETURN_REQUESTED");
    }

    @Test
    void anIneligibleActionNeverGetsAsFarAsAToken() {
        chatModel.script(
                toolCall("cancelOrder", "{\"orderNumber\":\"ORD-1002\",\"reason\":\"changed my mind\"}"),
                reply("ORD-1002 has already shipped, so I can't cancel it -- you can refuse delivery."));

        DocumentContext response = chat(ADA_KEY, "cancel ORD-1002 please");

        assertThat(response.read("$.pendingAction", Object.class)).isNull();
        assertThat(response.read("$.toolCalls[0].outcome", String.class)).isEqualTo("NOT_ALLOWED");
        assertThat(response.read("$.toolCalls[0].detail", String.class)).contains("ORDER_ALREADY_SHIPPED");
        assertThat(fixtures.statusOf("ORD-1002")).isEqualTo("SHIPPED");
    }

    @Test
    void theFullTrailIsAvailableForAudit() {
        DocumentContext response = askToCancel();
        String conversationId = response.read("$.conversationId");
        String token = response.read("$.pendingAction.confirmationToken");
        post("/api/v1/chat/confirmations/" + token, ADA_KEY, Map.of("decision", "APPROVE"));

        DocumentContext audit = json(get("/api/v1/chat/" + conversationId + "/audit", ADA_KEY));

        // Both halves are on the record, and the actor tells them apart: the model
        // asked, a human approved.
        List<String> actors = audit.read("$.toolInvocations[*].actor");
        List<String> outcomes = audit.read("$.toolInvocations[*].outcome");
        assertThat(actors).containsExactly("LLM_TOOL_CALL", "HUMAN_CONFIRMATION");
        assertThat(outcomes).containsExactly("CONFIRMATION_REQUIRED", "SUCCESS");
        assertThat(audit.read("$.toolInvocations[0].arguments", String.class)).contains("ORD-1001");
        assertThat(audit.read("$.pendingActions[0].status", String.class)).isEqualTo("EXECUTED");
    }
}
