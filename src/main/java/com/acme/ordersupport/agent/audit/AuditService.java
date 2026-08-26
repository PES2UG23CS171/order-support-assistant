package com.acme.ordersupport.agent.audit;

import com.acme.ordersupport.agent.SupportSession;
import com.acme.ordersupport.agent.ToolIdentityMismatchException;
import com.acme.ordersupport.order.error.ActionNotAllowedException;
import com.acme.ordersupport.order.error.OrderNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Wraps every tool call so that it lands in the audit log whatever happens to it.
 *
 * <p>The outcome is derived from what the call did, not from what the tool
 * remembered to report: a thrown {@link OrderNotFoundException} is a
 * {@code NOT_FOUND} row, a refused business rule is {@code NOT_ALLOWED}, and a
 * queued proposal is {@code CONFIRMATION_REQUIRED}. A tool cannot forget to log
 * itself, and a tool that throws cannot log itself as a success.
 */
@Service
public class AuditService {

    private final AuditWriter writer;
    private final AuditRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(AuditWriter writer, AuditRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.writer = writer;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public <T> T around(String toolName, Actor actor, SupportSession session, Map<String, Object> arguments,
                        Supplier<T> work) {
        long startedAt = System.nanoTime();
        UUID invocationId = UUID.randomUUID();
        try {
            T result = work.get();
            ToolOutcome outcome = result instanceof AuditedResult audited
                    ? audited.outcome() : ToolOutcome.SUCCESS;
            String detail = result instanceof AuditedResult audited ? audited.detail() : null;
            UUID pendingActionId = result instanceof AuditedResult audited ? audited.pendingActionId() : null;
            record(invocationId, toolName, actor, session, arguments, outcome, detail, pendingActionId,
                    startedAt);
            return result;
        }
        catch (OrderNotFoundException ex) {
            record(invocationId, toolName, actor, session, arguments, ToolOutcome.NOT_FOUND,
                    ex.getMessage(), null, startedAt);
            throw ex;
        }
        catch (ActionNotAllowedException ex) {
            record(invocationId, toolName, actor, session, arguments, ToolOutcome.NOT_ALLOWED,
                    "%s: %s".formatted(ex.code(), ex.getMessage()), null, startedAt);
            throw ex;
        }
        catch (ToolIdentityMismatchException ex) {
            record(invocationId, toolName, actor, session, arguments, ToolOutcome.NOT_FOUND,
                    ex.getMessage(), null, startedAt);
            throw ex;
        }
        catch (RuntimeException ex) {
            record(invocationId, toolName, actor, session, arguments, ToolOutcome.ERROR,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(), null, startedAt);
            throw ex;
        }
    }

    private void record(UUID invocationId, String toolName, Actor actor, SupportSession session,
                        Map<String, Object> arguments, ToolOutcome outcome, String detail,
                        UUID pendingActionId, long startedAt) {
        int durationMs = (int) ((System.nanoTime() - startedAt) / 1_000_000);
        writer.write(new ToolInvocation(0, invocationId, session.conversationId(), session.customerId(),
                actor, toolName, toJson(arguments), outcome, detail, pendingActionId, durationMs,
                clock.instant()));
    }

    public List<ToolInvocation> forConversation(UUID conversationId) {
        return repository.findByConversation(conversationId);
    }

    public List<ToolInvocation> since(UUID conversationId, long afterId) {
        return repository.findByConversationSince(conversationId, afterId);
    }

    public long highWaterMark(UUID conversationId) {
        return repository.highWaterMark(conversationId);
    }

    public List<ToolInvocation> recentForCustomer(UUID customerId, int limit) {
        return repository.findByCustomer(customerId, limit);
    }

    private String toJson(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        }
        catch (Exception ex) {
            return "{\"serialisationError\":\"%s\"}".formatted(ex.getClass().getSimpleName());
        }
    }
}
