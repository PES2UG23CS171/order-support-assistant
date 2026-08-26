package com.acme.ordersupport.agent.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes audit rows in their own transaction.
 *
 * <p>{@code REQUIRES_NEW} is the entire reason this is a separate bean. An audit
 * row that rolls back with the operation it was recording is worse than no audit
 * log, because the gaps are exactly where the interesting failures were. A refused
 * cancellation must leave a trace even though its transaction was thrown away.
 */
@Component
public class AuditWriter {

    private final AuditRepository repository;

    public AuditWriter(AuditRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long write(ToolInvocation invocation) {
        return repository.insert(invocation);
    }
}
