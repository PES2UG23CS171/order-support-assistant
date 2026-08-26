package com.acme.ordersupport.web;

import com.acme.ordersupport.agent.SupportAgent;
import com.acme.ordersupport.agent.audit.AuditService;
import com.acme.ordersupport.agent.audit.ToolInvocation;
import com.acme.ordersupport.agent.confirm.ConfirmationOutcome;
import com.acme.ordersupport.agent.confirm.ConfirmationService;
import com.acme.ordersupport.agent.confirm.PendingAction;
import com.acme.ordersupport.agent.model.ChatTurn;
import com.acme.ordersupport.config.CurrentCustomer;
import com.acme.ordersupport.guardrail.GuardrailEvent;
import com.acme.ordersupport.guardrail.GuardrailEventRepository;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final SupportAgent agent;
    private final ConfirmationService confirmations;
    private final AuditService audit;
    private final GuardrailEventRepository guardrailEvents;
    private final CurrentCustomer currentCustomer;

    public ChatController(SupportAgent agent, ConfirmationService confirmations, AuditService audit,
                          GuardrailEventRepository guardrailEvents, CurrentCustomer currentCustomer) {
        this.agent = agent;
        this.confirmations = confirmations;
        this.audit = audit;
        this.guardrailEvents = guardrailEvents;
        this.currentCustomer = currentCustomer;
    }

    @PostMapping
    public ChatTurn chat(@Valid @RequestBody com.acme.ordersupport.web.dto.ChatRequest request) {
        return agent.handle(currentCustomer.requireId(), request.conversationId(), request.message());
    }

    /**
     * Redeems a confirmation token.
     *
     * <p>This is the only route from a proposal to a state change, and reaching it
     * requires an authenticated request from the customer who owns the token. The
     * model has no way to call it: it is HTTP, not a tool.
     */
    @PostMapping("/confirmations/{token}")
    public ConfirmationOutcome confirm(@PathVariable UUID token,
                                       @Valid @RequestBody
                                       com.acme.ordersupport.web.dto.ConfirmationRequest request) {
        return confirmations.decide(currentCustomer.requireId(), token, request.decision());
    }

    /** Everything the assistant did in this conversation, for the auditability demo. */
    @GetMapping("/{conversationId}/audit")
    public ResponseEntity<Map<String, Object>> auditTrail(@PathVariable UUID conversationId) {
        UUID customerId = currentCustomer.requireId();
        List<ToolInvocation> invocations = audit.forConversation(conversationId).stream()
                .filter(invocation -> customerId.equals(invocation.customerId()))
                .toList();
        List<GuardrailEvent> guardrails = guardrailEvents.findByConversation(conversationId).stream()
                .filter(event -> customerId.equals(event.customerId()))
                .toList();
        List<PendingAction> pending = confirmations.forConversation(conversationId).stream()
                .filter(action -> customerId.equals(action.customerId()))
                .toList();
        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "toolInvocations", invocations,
                "guardrailEvents", guardrails,
                "pendingActions", pending));
    }
}
