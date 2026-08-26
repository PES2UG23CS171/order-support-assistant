package com.acme.ordersupport.agent.confirm;

import com.acme.ordersupport.agent.audit.Actor;
import com.acme.ordersupport.agent.audit.AuditWriter;
import com.acme.ordersupport.agent.audit.ToolInvocation;
import com.acme.ordersupport.agent.audit.ToolOutcome;
import com.acme.ordersupport.config.AppProperties;
import com.acme.ordersupport.order.model.ActionType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The human-in-the-loop gate.
 *
 * <p>Proposing is cheap and reversible; approving is neither. The two are split
 * across two authenticated HTTP requests with a database row between them, so
 * "the model decided to cancel an order" and "an order was cancelled" are
 * separated by a person.
 */
@Service
public class ConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationService.class);

    private final PendingActionRepository pendingActions;
    private final ActionExecutor executor;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    private final Clock clock;

    public ConfirmationService(PendingActionRepository pendingActions, ActionExecutor executor,
                               AuditWriter auditWriter, ObjectMapper objectMapper,
                               AppProperties properties, Clock clock) {
        this.pendingActions = pendingActions;
        this.executor = executor;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** Queues a proposal. Nothing about the order changes here. */
    @Transactional
    public PendingAction propose(UUID conversationId, UUID customerId, ActionType actionType, String toolName,
                                 UUID orderId, String orderNumber, Map<String, Object> arguments,
                                 String summary) {
        Instant now = clock.instant();
        PendingAction action = new PendingAction(
                UUID.randomUUID(), conversationId, customerId, actionType, toolName, orderId, orderNumber,
                toJson(arguments), summary, PendingActionStatus.AWAITING_CONFIRMATION, now,
                now.plus(properties.agent().confirmationTtl()), null, null);
        pendingActions.insert(action);
        log.info("Queued {} on order {} for confirmation (token {})", actionType, orderNumber, action.id());
        return action;
    }

    @Transactional(readOnly = true)
    public List<PendingAction> forConversation(UUID conversationId) {
        return pendingActions.findByConversation(conversationId);
    }

    /**
     * Redeems a token.
     *
     * <p>Ownership is checked against the row rather than the request, expiry
     * against the injected clock, and single use by claiming the row with a
     * compare-and-set before any work starts -- so a double-clicked Confirm button
     * cancels one order, not two.
     */
    @Transactional
    public ConfirmationOutcome decide(UUID customerId, UUID token, ConfirmationDecision decision) {
        PendingAction action = pendingActions.findById(token)
                .orElseThrow(() -> new ConfirmationErrors.NotFound(token));

        if (!action.customerId().equals(customerId)) {
            log.warn("Customer {} attempted to confirm pending action {} owned by {}",
                    customerId, token, action.customerId());
            audit(action, customerId, ToolOutcome.NOT_FOUND, "Confirmation attempted by a different customer");
            throw new ConfirmationErrors.NotFound(token);
        }

        Instant now = clock.instant();
        if (action.status() == PendingActionStatus.AWAITING_CONFIRMATION && action.expiredAt(now)) {
            pendingActions.transition(token, PendingActionStatus.AWAITING_CONFIRMATION,
                    PendingActionStatus.EXPIRED, now);
            audit(action, customerId, ToolOutcome.NOT_ALLOWED, "Confirmation expired before it was used");
            throw new ConfirmationErrors.Expired(token);
        }

        if (decision == ConfirmationDecision.REJECT) {
            if (pendingActions.transition(token, PendingActionStatus.AWAITING_CONFIRMATION,
                    PendingActionStatus.REJECTED, now) != 1) {
                throw new ConfirmationErrors.AlreadyDecided(token, action.status());
            }
            audit(action, customerId, ToolOutcome.SUCCESS, "Customer declined the proposed action");
            return new ConfirmationOutcome(token, PendingActionStatus.REJECTED,
                    "Nothing was changed. %s is untouched.".formatted(action.orderNumber()), null);
        }

        // Claim the token before doing any work. Whoever loses this race gets a
        // conflict rather than a second execution.
        if (pendingActions.transition(token, PendingActionStatus.AWAITING_CONFIRMATION,
                PendingActionStatus.EXECUTING, now) != 1) {
            throw new ConfirmationErrors.AlreadyDecided(token, action.status());
        }

        try {
            ActionOutcome outcome = executor.execute(action);
            pendingActions.transition(token, PendingActionStatus.EXECUTING, PendingActionStatus.EXECUTED, now);
            pendingActions.recordResult(token, toJson(outcome.payload()));
            audit(action, customerId, ToolOutcome.SUCCESS, "Confirmed and executed");
            log.info("Executed {} on order {} after customer confirmation (token {})",
                    action.actionType(), action.orderNumber(), token);
            return new ConfirmationOutcome(token, PendingActionStatus.EXECUTED, outcome.message(),
                    outcome.payload());
        }
        catch (RuntimeException ex) {
            // The order moved between proposal and confirmation, or the rule that
            // allowed it no longer holds. The proposal dies; the order is untouched.
            pendingActions.transition(token, PendingActionStatus.EXECUTING, PendingActionStatus.FAILED, now);
            audit(action, customerId, ToolOutcome.NOT_ALLOWED, ex.getMessage());
            throw ex;
        }
    }

    private void audit(PendingAction action, UUID customerId, ToolOutcome outcome, String detail) {
        auditWriter.write(new ToolInvocation(0, UUID.randomUUID(), action.conversationId(), customerId,
                Actor.HUMAN_CONFIRMATION, action.toolName(), action.argumentsJson(), outcome, detail,
                action.id(), 0, clock.instant()));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialise pending action payload", ex);
        }
    }
}
