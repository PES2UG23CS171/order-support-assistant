# Order Support Assistant — Technical Deep Dive

A complete walk through the system: every layer, the reasoning behind each decision, the
code that matters, and the questions an interviewer will actually ask.

**Repository:** `github.com/PES2UG23CS171/order-support-assistant`
**Stack:** Java 21 · Spring Boot 3.5.16 · Spring AI 1.1.8 · PostgreSQL 16 + pgvector · Flyway · Testcontainers · GitHub Actions
**Scale:** 88 main classes · 34 test classes · 215 tests · 93% line / 85% branch coverage

---

## 0. How to use this document

Read §1 and §2 to be able to *describe* the project. Read §5 to be able to *defend* it.
Read §9 and §11 the morning of the interview — those are the parts that separate someone
who built it from someone who watched a tutorial.

The single most important idea, if you remember nothing else:

> **The model cannot change anything. It can only ask.** Read tools return data.
> State-changing tools validate, write a row to `pending_actions`, and return a token.
> The only path from that token to a real change is a second authenticated HTTP request
> that the model has no way to make. The best possible outcome for a perfectly jailbroken
> prompt is a row in a table.

---

## 1. The 60-second version

An e-commerce customer support agent. A customer chats; a language model answers policy
questions from a retrieved corpus and takes action on their orders.

Three things make it more than a wrapper around a chat API:

1. **Tool calling against a governed service layer.** Five tools — `lookupOrder`,
   `trackDelivery`, `checkRefundStatus`, `cancelOrder`, `initiateReturn`. None of them
   touches the database. They call the same service methods the REST controllers call, so
   validation, authorization and optimistic locking apply identically whether the caller is
   a language model or the mobile app.

2. **RAG over policy documents with pgvector.** Four policy documents chunked on section
   headings, embedded, stored in Postgres. Retrieved passages are numbered into the prompt
   and the model must cite by number — so citations are *checkable*, not decorative.

3. **A human-in-the-loop guardrail.** State-changing tools return a proposal, not a change.
   Plus two guards that run *before* the model, and an append-only audit log of every tool
   call including the denied ones.

---

## 2. Tech stack — what, why, and what was rejected

### Core platform

| Choice | Why | Rejected alternative |
| --- | --- | --- |
| **Java 21** | Records for the domain model, pattern matching for `switch` over `ActionType`, virtual threads available if needed. LTS. | Java 17 — no records-in-switch ergonomics; Java 25 — less deployed in enterprise. |
| **Spring Boot 3.5.16** | Latest 3.5.x. The widely-deployed enterprise line. | Boot 4.x — current, but 3.5 is what most teams run and Spring AI 1.1 targets it. A one-line BOM bump upgrades. |
| **Spring AI 1.1.8** | First-class `ChatClient`, `@Tool` annotation, `ToolCallingManager`, `VectorStore` abstraction. Provider-agnostic. | LangChain4j — good library, but Spring AI integrates with Boot's auto-configuration, actuator and observability out of the box. Raw HTTP to OpenAI — would mean hand-rolling the tool-calling loop. |

### Persistence

| Choice | Why | Rejected alternative |
| --- | --- | --- |
| **Spring JDBC `JdbcClient`** | The queries are simple and the SQL is *visible*. No lazy-loading surprises, no N+1 hidden behind a proxy, no dirty-checking writing rows I did not intend. For a project whose whole thesis is "you can see exactly what the model can cause", hiding the SQL would undercut the argument. | **JPA/Hibernate** — an entity graph plus `@Version` would give optimistic locking for free, but at the cost of the SQL being generated rather than written. Worth naming in an interview: I did not avoid JPA because it is bad, I avoided it because *explicitness* is the point here. |
| **PostgreSQL 16 + pgvector** | One database for both the commerce domain and the embeddings. Transactional consistency between an order and the audit row about it. | A dedicated vector DB (Pinecone, Qdrant, Weaviate) — faster at very large scale, but introduces a second store, a second failure mode, and no transactional guarantee across them. At this corpus size (22 chunks) pgvector is not the bottleneck. |
| **Flyway** | Versioned, reviewable migrations. Critically, **I own the `vector_store` table** rather than letting `PgVectorStore` create it — so schema changes go through the same review and rollback path as everything else, and `initialize-schema` stays `false` in every environment. | Hibernate `ddl-auto` — unreviewable. Library-managed schema — invisible to code review. |

### Security

`spring-boot-starter-security` with a custom `OncePerRequestFilter` resolving an API key.
Not because API keys are the right production mechanism — they are a stand-in for OIDC —
but because the *point* is that identity is settled at the edge and the agent has no
influence over it. See §5.1.

### Testing

| Choice | Why |
| --- | --- |
| **JUnit 5 + AssertJ + Mockito** | Standard. AssertJ's fluent assertions read as sentences, which matters when a test name is a claim about behaviour. |
| **Testcontainers** | A real `pgvector/pgvector:pg16`. A test that stubs out the vector extension is not testing retrieval. |
| **JaCoCo** | Coverage gated at 85% line / 75% branch over the **merged** data from all three suites. |

### Why the OpenAI *and* Ollama starters are both on the classpath

`spring.ai.model.chat` selects between them. Switching providers is a property, not a code
change — which is itself the demonstration that nothing below `ChatClient` knows or cares
what model is behind it.

---

## 3. The request lifecycle, end to end

```
POST /api/v1/chat  { "message": "Please cancel ORD-1001" }
        │
        ▼
[1] ApiKeyAuthenticationFilter
        SHA-256 the X-API-Key header, look it up, put a CustomerPrincipal
        in the SecurityContext. No match → chain continues unauthenticated
        → SecurityConfig's entry point returns 401.
        │
        ▼
[2] ChatController.chat()
        currentCustomer.requireId() reads the principal. The customer id
        comes from here and nowhere else, for the rest of the request.
        │
        ▼
[3] SupportAgent.handle()
        a. resolveConversation()  — verify the caller owns this conversation
        b. injectionGuard.inspect()  ─┐  both run BEFORE the model,
        c. retriever.retrieve()       │  so a blocked message costs
        d. scopeGuard.assess()       ─┘  one vector search and no tokens
        e. callModel()
        │
        ▼
[4] ChatClient.prompt().messages(...).tools(supportTools).toolContext(...).call()
        Spring AI generates JSON schemas from the @Tool methods, sends them
        with the prompt, and runs the tool loop when the model calls one.
        │
        ▼
[5] SupportTools.cancelOrder()
        a. session(toolContext)  — assert tool-context identity == security-context identity
        b. audit.around(...)     — time it, classify the outcome, write an audit row
        c. orderService.lookup() + assessCancellation()  — same methods REST uses
        d. confirmations.propose()  — INSERT into pending_actions
        e. return ActionProposal   — status = CONFIRMATION_REQUIRED
        │
        ▼
[6] Response
        { reply, citations[], toolCalls[], pendingAction{token}, modelInvoked }
        toolCalls is read back OUT of the audit table, not accumulated in memory.
        │
        ▼
[7] POST /api/v1/chat/confirmations/{token}  { "decision": "APPROVE" }
        ConfirmationService.decide() — the only path to a state change.
```

---
## 4. Package layout

```
com.acme.ordersupport
├── config/        AppProperties, SecurityConfig, ApiKeyAuthenticationFilter,
│                  CustomerPrincipal, CurrentCustomer, AiConfig, CoreConfig
├── order/         the commerce domain — no knowledge that an LLM exists
│   ├── model/     enums, read-model records, CommercePolicy, Eligibility
│   ├── repo/      JdbcClient repositories, OrderRow
│   ├── error/     OrderNotFoundException, ActionNotAllowedException, ConcurrentUpdateException
│   └── OrderService, ReturnService, RefundService
├── agent/         everything the model touches
│   ├── model/     tool payloads (sealed ToolResult), ChatTurn, Citation
│   ├── audit/     AuditService, AuditWriter, AuditRepository, Actor, ToolOutcome
│   ├── confirm/   ConfirmationService, ActionExecutor, PendingAction, PendingActionStatus
│   └── SupportAgent, SupportTools, SupportSession, CitationExtractor, ChatClientProvider
├── guardrail/     PromptInjectionGuard, ScopeGuard, GuardDecision, GuardrailEventRepository
├── rag/           PolicyDocumentLoader, PolicyIngestionService, PolicyRetriever,
│                  HashingEmbeddingModel, PolicyChunk, RetrievedPolicy
└── web/           ChatController, OrderController, PolicyController, ApiExceptionHandler
```

**The dependency direction is the design.** `order/` has no import from `agent/`. The
commerce domain does not know an assistant exists. `agent/` depends on `order/`, the same
way `web/` does. If you deleted the entire `agent` package the business would still work —
which is precisely the claim "the LLM is just another client" means.

---

## 5. Layer by layer

### 5.1 Identity — the foundation everything else rests on

**The claim:** the model cannot act as another customer, because there is no channel
through which a model-generated value could become the acting customer.

**The mechanism, in three parts.**

*Part one — identity is established at the edge, before any agent code runs.*

```java
// ApiKeyAuthenticationFilter
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                FilterChain chain) throws ServletException, IOException {
    String apiKey = request.getHeader(HEADER);
    if (StringUtils.hasText(apiKey) && SecurityContextHolder.getContext().getAuthentication() == null) {
        customers.findByApiKeyHash(sha256Hex(apiKey)).ifPresent(principal -> {
            var authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        });
    }
    chain.doFilter(request, response);
}
```

Note it **hashes** and looks up rather than comparing plaintext — the raw key is never
stored. Note also it does not reject on failure; it leaves the context empty and lets
`SecurityConfig`'s `authenticationEntryPoint` produce a consistent 401. A filter that
writes its own error response duplicates error handling.

*Part two — identity is read from exactly one place.*

```java
@Component
public class CurrentCustomer {
    public UUID requireId() { return require().customerId(); }
    // ... reads SecurityContextHolder, never takes an argument
}
```

**Why this class exists at all:** so that "who is asking?" has one answer with one
implementation. No method anywhere downstream accepts a customer id as a parameter.

*Part three — the tools have no identity parameter.*

This is the load-bearing detail. Spring AI generates the JSON schema the model sees from
the method signature. Since no `@Tool` method declares a customer id, **no customer id
appears in the schema**, so there is nothing for the model to hallucinate, and no
instruction in a prompt injection that could populate it. You cannot inject a field that
does not exist.

*Defence in depth — the two contexts must agree.*

```java
private SupportSession session(ToolContext toolContext) {
    SupportSession session = SupportSession.from(toolContext);
    UUID authenticated = currentCustomer.requireId();
    if (!authenticated.equals(session.customerId())) {
        throw new ToolIdentityMismatchException(
                "Tool context customer %s does not match the authenticated customer %s"
                        .formatted(session.customerId(), authenticated));
    }
    return session;
}
```

`ToolContext` is set by the agent when it builds the request and is **not part of any
tool's JSON schema** — the model cannot see or write it. The `SecurityContext` is set by
the filter. Two independent paths, asserted equal.

> **Interviewer: "Why bother, if it's unreachable?"**
> Today `ChatClient.call()` is blocking, so the `ThreadLocal` behind `SecurityContextHolder`
> follows the work and the two can't diverge. The day someone switches to `.stream()` or a
> thread pool, that stops being true — and the failure would be silent: a tool acting on
> the wrong account. One comparison converts a silent data breach into a loud exception.

---

### 5.2 The domain — policy as code, sitting next to policy as prose

`CommercePolicy` holds the numbers the service enforces, each cross-referenced to the
section of the corpus the assistant quotes:

```java
public final class CommercePolicy {
    /** returns-policy.md, "Standard return window". */
    public static final int STANDARD_RETURN_WINDOW_DAYS = 30;
    /** returns-policy.md, "Standard return window", extended window for furniture. */
    public static final int FURNITURE_RETURN_WINDOW_DAYS = 60;
    /** returns-policy.md, "Return shipping costs". */
    public static final BigDecimal CHANGE_OF_MIND_LABEL_FEE = new BigDecimal("6.95");

    public static int returnWindowDays(List<OrderLineView> returnableLines) {
        boolean furnitureOnly = !returnableLines.isEmpty()
                && returnableLines.stream().allMatch(line -> FURNITURE.equalsIgnoreCase(line.category()));
        return furnitureOnly ? FURNITURE_RETURN_WINDOW_DAYS : STANDARD_RETURN_WINDOW_DAYS;
    }

    public static boolean sellerAtFault(String reason) { /* keyword list */ }
}
```

**Why this matters more than it looks.** The most likely production failure for a
retrieval-grounded support agent is *drift*: the bot quotes "30 days" from a document while
the backend enforces 45, and now you have a support agent making promises the system will
refuse. Keeping the constant beside the citation makes the drift visible in code review.

`sellerAtFault` is a deliberate non-use of the model:

```java
private static final List<String> SELLER_AT_FAULT_PHRASES = List.of(
        "defect", "faulty", "broken", "damaged", "cracked", "not working", ...);
```

> **Interviewer: "Why not let the LLM classify the return reason?"**
> Because it decides who pays the $6.95. A rule that moves money needs to be inspectable,
> unit-testable and deterministic — and a customer should not be able to talk the model
> into waiving a fee. The model's job is to *understand* the customer; the service's job is
> to *decide*. That split is the whole architecture in one method.

---

### 5.3 Persistence — optimistic locking, done by hand and on purpose

```java
public int updateStatus(UUID orderId, OrderStatus newStatus, int expectedVersion, Instant cancelledAt) {
    return jdbc.sql("""
                    UPDATE orders
                       SET status = :status,
                           cancelled_at = COALESCE(:cancelledAt, cancelled_at),
                           version = version + 1
                     WHERE id = :id AND version = :expectedVersion
                    """)
            .param("status", newStatus.name())
            ...
            .update();          // returns rows affected
}
```

`WHERE ... AND version = :expectedVersion` is a **compare-and-set**. It returns `1` if this
writer won and `0` if someone else moved the row first. The service turns `0` into an
exception rather than letting it pass as a silent no-op:

```java
if (orders.updateStatus(order.id(), OrderStatus.CANCELLED, order.version(), now) != 1) {
    throw new ConcurrentUpdateException(orderNumber);
}
```

**Without this**, two concurrent cancellations would both write `CANCELLED` and both open a
refund — the customer gets paid twice. `OrderServiceIT` fires six concurrent cancellations
at one order and asserts exactly one succeeds and exactly one refund row exists.

*Small detail worth knowing:* `Rows.instant()` maps `timestamptz` via `OffsetDateTime`,
because pgjdbc does not support `getObject(col, Instant.class)`. Getting that wrong throws
at runtime, not compile time.

---

### 5.4 The service layer — one authorization choke point

```java
OrderRow requireOwned(UUID customerId, String orderNumber) {
    OrderRow order = orders.findByOrderNumber(orderNumber).orElseThrow(
            () -> new OrderNotFoundException(orderNumber));
    if (!order.customerId().equals(customerId)) {
        // The caller is told only that it was not found. The real reason is
        // recorded here, where it is useful and not exploitable.
        log.warn("Customer {} attempted to access order {} owned by {}",
                customerId, orderNumber, order.customerId());
        throw new OrderNotFoundException(orderNumber);
    }
    return order;
}
```

Every read and every write in `OrderService`, `ReturnService` and `RefundService` funnels
through this method. "Can customer X touch order Y" is answered in one place, so it cannot
be forgotten at a new call site.

> **Interviewer: "Why 404 and not 403?"**
> A 403 confirms the order number is real. Given a chat box and a 403/404 distinction, you
> can enumerate valid order numbers — the API becomes an oracle. Both cases return
> byte-identical responses apart from the number the caller supplied, and
> `SecuritySystemIT.aNonexistentOrderAndSomeoneElsesOrderAreIndistinguishable` asserts
> exactly that. The real reason lives in the log and the audit table, where it is useful
> and not reachable by an attacker.

---

### 5.5 Eligibility as a value — the trick that makes the confirmation gate honest

```java
public record Eligibility(boolean allowed, String code, String explanation) {
    public static Eligibility allowed(String explanation) { ... }
    public static Eligibility denied(String code, String explanation) { ... }
}
```

Separating *"may this happen?"* from *"do it"* is what lets a tool describe an action
truthfully before a human approves it. `assessCancellation()` runs the same rules `cancel()`
will run, so the summary shown to the customer cannot promise something the service would
then refuse.

**But the execution path re-runs the check anyway**, inside its own transaction:

```java
@Transactional
public CancellationResult cancel(UUID customerId, String orderNumber, String reason) {
    OrderRow order = requireOwned(customerId, orderNumber);
    Eligibility eligibility = cancellationEligibility(order);
    if (!eligibility.allowed()) {
        throw ActionNotAllowedException.of(eligibility);
    }
    ...
}
```

> **Interviewer: "Isn't that a redundant check?"**
> No — it's a TOCTOU (time-of-check to time-of-use) defence. Between the proposal and the
> confirmation a human is reading a screen; that could be ten minutes, during which the
> warehouse may print a shipping label. A decision made a minute ago is not evidence about
> the row as it stands now. `ConfirmationFlowIT` covers this: propose, mutate the order
> behind it, then approve — the proposal moves to `FAILED` and the order is untouched.

Two-field design note: `code` is stable and machine-readable (tests assert on
`ORDER_ALREADY_SHIPPED`), `explanation` is customer-safe prose written once in the service
and reused by the tool, the REST error body and the model's context. The wording cannot
drift between channels because there is only one copy of it.

---

### 5.6 The tool layer — three properties, and why each is load-bearing

```java
@Tool(name = "cancelOrder", description = """
        Prepare a cancellation of one of the signed-in customer's orders. This does NOT cancel the
        order. It checks the order is still cancellable and returns a proposal with a confirmation
        token that the customer has to approve in the application before anything happens. ...""")
public ToolResult cancelOrder(
        @ToolParam(description = "The order number to cancel, for example ORD-1001") String orderNumber,
        @ToolParam(description = "The customer's reason for cancelling, in their own words",
                   required = false) String reason,
        ToolContext toolContext) {

    SupportSession session = session(toolContext);
    Map<String, Object> arguments = args("orderNumber", orderNumber, "reason", reason);
    return audit.around("cancelOrder", Actor.LLM_TOOL_CALL, session, arguments, () -> {
        try {
            OrderView order = orderService.lookup(session.customerId(), orderNumber);
            Eligibility eligibility = orderService.assessCancellation(session.customerId(), orderNumber);
            if (!eligibility.allowed()) {
                return ToolRefusal.notAllowed(orderNumber, eligibility.code(), eligibility.explanation());
            }
            String summary = ("Cancel order %s (%s %s, currently %s). A full refund to the original "
                              + "payment method will be opened. This cannot be undone.")
                    .formatted(orderNumber, order.currency(), order.totalAmount(), order.status());
            PendingAction proposal = confirmations.propose(session.conversationId(), session.customerId(),
                    ActionType.CANCEL_ORDER, "cancelOrder", order.id(), orderNumber, arguments, summary);
            return ActionProposal.from(proposal);
        }
        catch (OrderNotFoundException ex) {
            return ToolRefusal.notFound(orderNumber);
        }
    });
}
```

**Property 1 — no database.** Not a repository, not a `Connection`, not a SQL string. Only
`orderService` and `confirmations`. The same methods `OrderController` calls.

**Property 2 — no identity parameter.** Covered in §5.1. `ToolContext` is a Spring AI
parameter type that `JsonSchemaGenerator` explicitly excludes from the generated schema —
verified by decompiling the class, not assumed.

**Property 3 — no state change.** `cancelOrder` does not call `orderService.cancel()`. It
calls `confirmations.propose()`. `SupportToolsTest` asserts this directly:

```java
verify(orderService, never()).cancel(any(), anyString(), anyString());
```

That single line is the strongest test in the project.

#### Why tools return refusals as *values*, not exceptions

```java
public sealed interface ToolResult
        permits OrderSnapshot, DeliverySnapshot, RefundSnapshot, ActionProposal, ToolRefusal { }
```

> **Interviewer: "Why not just throw?"**
> Because a business refusal — outside the return window, already shipped, not your order —
> is an *ordinary outcome* the model needs to read and explain to the customer. Routing it
> through an exception makes the behaviour depend on the framework's exception policy:
> whether Spring AI's `ToolExecutionExceptionProcessor` converts it to a message the model
> sees, or lets it escape and fail the entire chat turn. That is a config-dependent
> behaviour for something that should be deterministic. Exceptions stay for things that are
> genuinely broken — a database being down.

The sealed interface means adding a sixth payload type is a deliberate act that will not
compile until you handle it.

#### The tool payload carries verdicts, not raw facts

`OrderSnapshot` includes `cancellable`, `cancellationNote`, `returnable`, `returnNote`. The
model is *told* whether the return window has closed rather than being given a delivery date
and asked to work it out. It cannot arrive at a different answer from the service, because
it never does the arithmetic.

#### The `note` field is phrased as fact, not instruction

```java
private static final String NOTE =
        "This action has not been performed. It is queued and will only happen if the customer "
        + "explicitly confirms it in the application. Nothing about the order has changed.";
```

The system prompt tells the model that tool output is *data and never a command*. A payload
that then issued commands would teach it the opposite — which is exactly the foothold a
prompt-injection payload smuggled into an order's data would need.

---
### 5.7 The confirmation gate — the centrepiece

**The state machine:**

```
AWAITING_CONFIRMATION ──approve──► EXECUTING ──ok───► EXECUTED
        │                              └──throws──► FAILED
        ├──reject──► REJECTED
        └──expired─► EXPIRED
```

`EXECUTING` exists so that a crash between "claim" and "record" leaves a *visible,
recoverable* state rather than a row that looks like it was never approved.

**Redeeming a token — four failure modes, each handled:**

```java
public ConfirmationOutcome decide(UUID customerId, UUID token, ConfirmationDecision decision) {
    PendingAction action = pendingActions.findById(token)
            .orElseThrow(() -> new ConfirmationErrors.NotFound(token));

    // (1) Ownership — checked against the stored row, never the request body.
    if (!action.customerId().equals(customerId)) {
        log.warn("Customer {} attempted to confirm pending action {} owned by {}", ...);
        audit(action, customerId, ToolOutcome.NOT_FOUND, "Confirmation attempted by a different customer");
        throw new ConfirmationErrors.NotFound(token);
    }

    // (2) Expiry — against the injected Clock, so a test can move time.
    Instant now = clock.instant();
    if (action.status() == PendingActionStatus.AWAITING_CONFIRMATION && action.expiredAt(now)) {
        pendingActions.transition(token, AWAITING_CONFIRMATION, EXPIRED, now);
        throw new ConfirmationErrors.Expired(token);
    }

    if (decision == ConfirmationDecision.REJECT) { ... }

    // (3) Single use — claim the row with a compare-and-set BEFORE any work starts.
    if (pendingActions.transition(token, AWAITING_CONFIRMATION, EXECUTING, now) != 1) {
        throw new ConfirmationErrors.AlreadyDecided(token, action.status());
    }

    try {
        ActionOutcome outcome = executor.execute(action);
        pendingActions.transition(token, EXECUTING, EXECUTED, now);
        pendingActions.recordResult(token, toJson(outcome.payload()));
        return new ConfirmationOutcome(token, EXECUTED, outcome.message(), outcome.payload());
    }
    catch (RuntimeException ex) {
        // (4) The rule that allowed it no longer holds. The proposal dies; the order is untouched.
        pendingActions.transition(token, EXECUTING, FAILED, now);
        audit(action, customerId, ToolOutcome.NOT_ALLOWED, ex.getMessage());
        throw ex;
    }
}
```

**The compare-and-set is the whole single-use guarantee:**

```sql
UPDATE pending_actions SET status = 'EXECUTING'
 WHERE id = :id AND status = 'AWAITING_CONFIRMATION'
```

Two confirmations arriving together both attempt this; Postgres row-locks serialise them,
and the loser matches zero rows and gets a 409. `ConfirmationFlowIT` fires six concurrent
approvals and asserts exactly one execution and exactly one refund.

#### Why `decide()` is deliberately NOT `@Transactional`

This is the single best architecture question in the project, and it came from a real bug.

> **Interviewer: "Shouldn't that whole method be one transaction?"**
>
> It was, and it was wrong twice over.
>
> **First**, it broke the error path. When the executor threw, the code marked the proposal
> `FAILED` and rethrew — and the rethrow rolled back the transaction *including the FAILED
> mark*. The row went back to `AWAITING_CONFIRMATION`: a proposal permanently awaiting a
> confirmation that could never succeed, because the underlying action was no longer legal.
> The integration test caught it; no unit test with a mocked repository ever could, because
> rollback is a property of a real transaction manager.
>
> **Second**, it would have defeated the claim. If the compare-and-set is inside the same
> transaction as the work, it is invisible to a concurrent request until commit — which is
> the exact opposite of what claiming a token is for.
>
> So it is a **saga**: claim, execute, record — three steps, each committing on its own.
> The action itself is still atomic, because `OrderService.cancel()` opens its own
> transaction. What is not atomic is the bookkeeping around it, and that is a deliberate
> trade with a named failure mode: a crash mid-flight leaves the row in `EXECUTING`, which
> is queryable and recoverable, rather than in a state that lies about what happened.

#### `ActionExecutor` — the only door

```java
public ActionOutcome execute(PendingAction action) {
    String reason = argument(action, "reason", "Requested by the customer in support chat");
    return switch (action.actionType()) {
        case CANCEL_ORDER -> { ... orderService.cancel(action.customerId(), action.orderNumber(), reason); }
        case INITIATE_RETURN -> { ... returnService.initiateReturn(action.customerId(), ...); }
    };
}
```

Reached from exactly one place, and it takes `action.customerId()` — **from the stored row**,
not from the current request body. A confirmation cannot be redirected onto a different
account by its payload. The `switch` over a sealed enum means a third action type will not
compile until it is handled here.

---

### 5.8 Audit — the log that survives what it records

```java
@Component
public class AuditWriter {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long write(ToolInvocation invocation) {
        return repository.insert(invocation);
    }
}
```

> **Interviewer: "Why is this a separate bean?"**
> Because `REQUIRES_NEW` only works through the Spring proxy. If `write()` lived in
> `AuditService` and were called from another method in the same class, the self-invocation
> would bypass the proxy and the new transaction would never start — one of the most common
> silent Spring bugs. Making it a separate collaborator makes the proxy boundary explicit.
>
> And the reason it needs a new transaction at all: **an audit row that rolls back with the
> operation it was recording is worse than no audit log**, because the gaps are exactly
> where the interesting failures were. A refused cancellation must leave a trace even though
> its transaction was thrown away. `AuditTrailIT` asserts precisely this — run a failing
> cancel inside a `TransactionTemplate`, let it roll back, and the audit row is still there.

**Outcomes are derived from what happened, not from what the tool remembered to report:**

```java
public <T> T around(String toolName, Actor actor, SupportSession session,
                    Map<String, Object> arguments, Supplier<T> work) {
    long startedAt = System.nanoTime();
    try {
        T result = work.get();
        ToolOutcome outcome = result instanceof AuditedResult audited
                ? audited.outcome() : ToolOutcome.SUCCESS;
        ...
        return result;
    }
    catch (OrderNotFoundException ex)     { record(..., NOT_FOUND, ...);   throw ex; }
    catch (ActionNotAllowedException ex)  { record(..., NOT_ALLOWED, ...); throw ex; }
    catch (RuntimeException ex)           { record(..., ERROR, ...);       throw ex; }
}
```

A tool cannot forget to log itself, and a tool that throws cannot log itself as a success.

**And the payoff — the chat response reads its tool list back out of the table:**

```java
private List<ToolCallSummary> toolCallsSince(UUID conversationId, long watermark) {
    return audit.since(conversationId, watermark).stream().map(SupportAgent::toSummary).toList();
}
```

> **Interviewer: "Why not just collect them in a list as they run?"**
> Because then the response and the durable record are two sources of truth that can
> disagree. Reading from the table means the API cannot claim a tool ran that was never
> logged, or hide one that was. It also paid off unexpectedly: when a weak local model
> *narrated* a cancellation it never requested, the response correctly showed
> `toolCalls: []` and `pendingAction: null` — the prose lied, the structured data did not.

`Actor` distinguishes `LLM_TOOL_CALL`, `HUMAN_CONFIRMATION` and `REST_CLIENT` in one table,
so "the model asked, a human approved" is a two-row story you can read off.

---

### 5.9 Guardrails — two, both ahead of the model

```java
GuardDecision injection = injectionGuard.inspect(message);
if (injection.blocked()) return refuse(...);          // no LLM call

List<RetrievedPolicy> passages = retriever.retrieve(message);

GuardDecision scope = scopeGuard.assess(message, passages, continuingConversation);
if (scope.blocked()) return refuse(...);              // no LLM call
```

**Ordering is deliberate:** retrieval runs *before* the scope guard, because a strong policy
match is itself evidence the question is in scope — cheaper than a classifier, and it reuses
work the turn needs anyway.

`ScopeGuard` allows on any of four signals — an order number, a domain word, an ordinary
greeting, or a policy passage — with explicit off-topic patterns overriding all of them:

```java
for (Pattern pattern : OFF_TOPIC) {                    // "what return should I expect
    if (pattern.matcher(normalised).find()) { ... }    //  on this stock?" contains a
}                                                      //  domain word and is still off-topic
if (ORDER_NUMBER.matcher(normalised).find())  return GuardDecision.allow();
if (GREETING.matcher(message.strip()).matches()) return GuardDecision.allow();
for (String word : normalised.split("[^a-z0-9]+")) {
    if (DOMAIN_WORDS.contains(word)) return GuardDecision.allow();
}
if (!passages.isEmpty()) return GuardDecision.allow();
if (continuingConversation) return GuardDecision.allow();   // see §9.5
return GuardDecision.block(NAME, "No order reference, no domain vocabulary ...", REPLY);
```

**Be honest about what these are.** From the class javadoc:

> It is a cheap, deterministic first line that catches the obvious and the automated. It is
> **not** a solution to prompt injection — patterns are trivially evaded by rephrasing, and
> anyone claiming otherwise is selling something. The actual defence is structural: tools
> that take no identity parameter, a service layer that authorizes every call, and state
> changes that require a human. A prompt that gets past every pattern here still cannot
> cancel an order.

What they buy: a clean refusal, an audit row, and **no tokens spent on an attack**. The
system tests assert `modelInvoked == false`, not merely that the reply was a refusal — the
guard's value is that the completion never happened.

The refusal text also never explains what tripped, because telling an attacker which pattern
caught them is free tuning feedback. The reason goes to `guardrail_events`.

---

### 5.10 RAG — chunking, embedding, retrieval, citation

#### Chunking on section headings, not token counts

```java
private static Map<String, String> splitSections(String body) {
    // splits on "## " headings, preserving order
}
static List<String> splitLongSection(String section, int maxChunkChars, int overlapChars) {
    // paragraph-aligned fallback with overlap, only when a section runs long
}
```

The corpus was written one-question-per-heading, so headings are natural semantic
boundaries. **A chunk that begins mid-rule cites badly** — the customer gets a fragment with
no subject. Fixed-size chunking would cut "you have 30 days from the delivery date" in half.
The 1200-char fallback with 150-char overlap exists so no chunk is unbounded.

Every chunk's text starts with `"{title} -- {section}"`, so a passage retrieved alone still
carries its own attribution.

#### Deterministic ids make re-ingestion an upsert

```java
static String deterministicId(String policyId, String section, int index) {
    String key = policyId + '#' + section.toLowerCase(Locale.ROOT) + '#' + index;
    return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
}
```

`PgVectorStore` inserts with `ON CONFLICT (id) DO UPDATE`, so a stable UUIDv3 per
(policy, section, index) turns re-ingestion into an in-place overwrite instead of a
duplicate. **Random UUIDs would silently double the corpus on every restart** — and the
symptom would be gradually worse retrieval, which is very hard to diagnose.

#### Embedding is expensive, so it is skipped when nothing changed

```java
private boolean upToDate(String policyId, String docHash, int expectedChunks) {
    Integer count = jdbc.sql("""
                    SELECT count(*) FROM vector_store
                     WHERE metadata ->> 'policy_id' = :policyId
                       AND metadata ->> 'doc_hash' = :docHash
                    """) ...;
    return count != null && count == expectedChunks;
}
```

Each document carries a SHA-256 of its source text. Unchanged → skip entirely. Changed →
`removeStaleRevisions()` deletes the old revision's chunks first, so **a section deleted
from a document stops being retrievable** rather than lingering forever. A restart costs
nothing; an edit re-embeds one document.

#### Citations are checkable, not decorative

Retrieved passages go into the system prompt numbered:

```java
public static String asContextBlock(List<RetrievedPolicy> passages) {
    // [1] source=returns-policy#Standard return window version=2026-03-01
    // <chunk text>
}
```

The model is told to mark each claim with its number. Then:

```java
public static List<Citation> citedIn(String reply, List<RetrievedPolicy> passages) {
    Matcher matcher = MARKER.matcher(reply);          // \[(\d{1,2})\]
    while (matcher.find()) {
        int marker = Integer.parseInt(matcher.group(1));
        if (marker >= 1 && marker <= passages.size()) { markers.add(marker); }
    }
    ...
}
```

**A fabricated `[7]` against four passages resolves to nothing and never becomes a
citation.** The response reports *both* `citations` (what the answer actually used) and
`retrievedPassages` (everything offered) — and the gap between those two numbers is the
useful signal when tuning `topK` and the threshold.

#### The offline embedding model, and why it lives in `src/main`

`HashingEmbeddingModel` is signed feature hashing over a TF-weighted bag of words,
L2-normalised so a dot product is a cosine, output at 1536 dimensions to match
`text-embedding-3-small` — **so one `vector(1536)` column serves both modes and switching
needs no migration.**

```java
int hash = token.hashCode();
int bucket = Math.floorMod(hash, DIMENSIONS);
int sign = ((hash >>> 16) & 1) == 0 ? 1 : -1;   // signed hashing: collisions cancel
vector[bucket] += sign;
...
float magnitude = (float) (1 + Math.log(Math.abs(vector[i])));   // sublinear TF
```

`String.hashCode()` is specified by the JLS, so this is stable across JVMs and machines —
the corpus embeds identically on every CI run and a retrieval assertion cannot flake.

> **Interviewer: "Isn't shipping a fake embedding model in production code a smell?"**
> It would be if it pretended to be semantic. It does not: the javadoc says plainly that it
> matches on shared vocabulary, not meaning, and that "how long do I have to send something
> back" will not find the returns section unless the words overlap. What it buys is that
> retrieval is exercised *for real* in CI — real pgvector, real HNSW cosine ranking, real
> ranking assertions — with no API key, no network and no per-run cost. In CI, a flaky or
> billable assertion is worse than a narrow one.

#### The similarity threshold belongs to the model, not the app

```java
public double defaultSimilarityThreshold() {
    return properties.ai().offline()
            ? properties.rag().offlineSimilarityThreshold()   // 0.20
            : properties.rag().similarityThreshold();         // 0.35
}
```

Measured on this corpus: the offline model scores relevant passages **0.26–0.43** and
irrelevant ones **0.07–0.16**. Dense hosted embeddings sit much higher and need ~0.35. See
§9.4 for the bug this caused.

---

### 5.11 Agent orchestration

```java
public ChatTurn handle(UUID customerId, UUID requestedConversationId, String message) {
    UUID conversationId = resolveConversation(customerId, requestedConversationId);
    SupportSession session = new SupportSession(conversationId, customerId);

    boolean continuingConversation = !conversations.recentMessages(conversationId, 1).isEmpty();
    conversations.append(conversationId, StoredMessage.USER, message);
    long auditWatermark = audit.highWaterMark(conversationId);
    ...
}
```

Two details worth defending:

**`resolveConversation` verifies ownership.** A conversation id travels in a request body
and is trivially guessable to anyone who has seen one. Without this check it would be enough
to read someone else's support history — and it returns `ConversationNotFoundException` for
both "doesn't exist" and "isn't yours", same non-enumeration reasoning as §5.4.

**The audit watermark** is read before the turn so `toolCallsSince()` returns only *this*
turn's calls, not the conversation's whole history.

**The prompt is assembled by concatenation, not templating:**

```java
String systemPrompt(List<RetrievedPolicy> passages) {
    return systemPromptTemplate + "\n\n## Policy passages\n\n"
           + PolicyRetriever.asContextBlock(passages) + "\n";
}
```

Spring AI's `ChatClient.system(String)` renders through StringTemplate. Policy text is
arbitrary prose that will one day contain a `{` or a `$`, and a templating pass over
document content is a production failure waiting for the wrong edit. Passing
`new SystemMessage(text)` via `.messages(...)` sidesteps rendering entirely.

**`ChatClientProvider` makes the model optional:**

```java
public ChatClientProvider(ObjectProvider<ChatModel> chatModels,
                          ObjectProvider<ChatClient.Builder> builders) {
    // Keyed off the model rather than the builder. The builder's bean definition always
    // exists, and asking an ObjectProvider for it when no model is configured propagates
    // the creation failure instead of returning null.
    this.chatClient = chatModels.getIfAvailable() != null ? builders.getObject().build() : null;
}
```

See §9.6 — this came from a real container failure.

---

### 5.12 Web layer — error semantics as an API contract

RFC 7807 `ProblemDetail`, with status codes chosen so a client can distinguish cases without
parsing prose:

| Status | Meaning | Example |
| --- | --- | --- |
| 401 | no/invalid API key | missing `X-API-Key` |
| 404 | not yours, or not there | someone else's order; unknown confirmation token |
| 409 | the rules say no, or someone got there first | already shipped; replayed token |
| 410 | expired | confirmation past its TTL |
| 422 | malformed request | blank chat message |
| 503 | assistant not configured | no chat model on this instance |

Every body carries a stable `code` field (`ORDER_ALREADY_SHIPPED`,
`CONFIRMATION_ALREADY_DECIDED`) that tests assert on — prose can be reworded without
breaking clients.

**`OrderController` exists to prove a point.** It calls the same service methods the tools
call, and `RestApiSystemIT` asserts both callers get the same refusals.

> **Interviewer: "Why does the REST API skip the confirmation gate?"**
> Because domain rules apply to everyone, but human-in-the-loop applies to the caller that
> cannot be trusted to have asked. A first-party client has already shown the customer its
> own confirmation dialog. The gate is an *agent-layer* control, not a domain invariant —
> and being able to articulate that distinction is the difference between having a guardrail
> and having a superstition.

---
## 6. Database schema

Three migrations. `V1` and `V2` run everywhere; `V900` lives in `classpath:db/seed`, a
location only the `local` and `test` profiles add to `spring.flyway.locations`, so
**production never sees demo fixtures**.

### V1 — commerce domain + guardrail state

```sql
CREATE TABLE orders (
    id               uuid          PRIMARY KEY,
    order_number     text          NOT NULL UNIQUE,
    customer_id      uuid          NOT NULL REFERENCES customers (id),
    status           text          NOT NULL,
    total_amount     numeric(12,2) NOT NULL CHECK (total_amount >= 0),
    ...
    version          integer       NOT NULL DEFAULT 0   -- optimistic lock
);
```

`numeric(12,2)` not `float` — money is never floating point. `text` for status, not a
Postgres enum: adding a status to a PG enum requires DDL, whereas the Java enum is the
source of truth and a `valueOf` failure is a loud, fast error.

The two tables that make the design real:

```sql
-- A side effect the model has *proposed* but not performed. The id is the token.
CREATE TABLE pending_actions (
    id              uuid        PRIMARY KEY,
    conversation_id uuid        NOT NULL REFERENCES conversations (id),
    customer_id     uuid        NOT NULL REFERENCES customers (id),
    action_type     text        NOT NULL,
    order_id        uuid        NOT NULL REFERENCES orders (id),
    arguments       jsonb       NOT NULL,
    summary         text        NOT NULL,
    status          text        NOT NULL,
    expires_at      timestamptz NOT NULL,
    result          jsonb
);

-- Append-only. Every tool call, including the denied ones.
CREATE TABLE tool_invocations (
    id                bigserial   PRIMARY KEY,
    conversation_id   uuid,               -- nullable: REST calls have no conversation
    customer_id       uuid,
    actor             text        NOT NULL,   -- LLM_TOOL_CALL | HUMAN_CONFIRMATION | REST_CLIENT
    tool_name         text        NOT NULL,
    arguments         jsonb       NOT NULL,
    outcome           text        NOT NULL,
    detail            text,
    pending_action_id uuid,
    duration_ms       integer     NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now()
);
```

`bigserial` on the audit table gives a monotonic id, which is what the watermark in
§5.11 relies on to slice one turn out of a conversation.

### V2 — the vector store, owned by Flyway

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE vector_store (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    content   text,
    metadata  json,
    embedding vector(1536)
);

CREATE INDEX idx_vector_store_embedding
    ON vector_store USING hnsw (embedding vector_cosine_ops);
```

Two details an interviewer may probe:

- **The column shapes match what `PgVectorStore` expects exactly** — I decompiled the class
  and read its `CREATE TABLE` format string rather than guessing. `metadata` is `json`
  because that is what the library creates; its insert casts with `?::jsonb`, which
  Postgres accepts into a `json` column.
- **`vector_cosine_ops` is not optional.** The store's default `COSINE_DISTANCE` issues
  `embedding <=> ?`, and an HNSW index built with `vector_l2_ops` would simply not be used —
  the query still works, silently, via sequential scan. That is the kind of thing that is
  fine at 22 chunks and a production incident at 22 million.

---

## 7. Testing — three suites, and what each one can prove

```bash
make unit          # 138 tests · no Docker · seconds
make integration   #  37 tests · Testcontainers pgvector
make system        #  40 tests · app on a real port, over HTTP
make verify        # all three + merged coverage gate
```

Separate jobs so **a failure names its own layer**: red `unit` is a broken rule; red
`system` with green `unit` is a wiring or contract problem.

### Unit — rules, guards, tool boundary

Mocks and a fixed clock. Time is injected everywhere:

```java
public static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
public static Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
```

Return windows and token expiry are asserted by *moving the clock*, not by sleeping. A test
that sleeps ten minutes to prove a token expired is a test nobody runs.

The most valuable ones:

```java
// The whole guardrail thesis, in one assertion.
verify(orderService, never()).cancel(any(), anyString(), anyString());

// Non-enumeration, asserted rather than assumed.
assertThat(unknown.replace("ORD-9999", "X")).isEqualTo(notMine.replace("ORD-2001", "X"));
```

### Integration — real database, real pgvector, real concurrency

One Testcontainers instance for the whole run (singleton pattern in a static initialiser,
not `@Container`, which would start one per class):

```java
protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
static { POSTGRES.start(); }
```

This suite proves what unit tests structurally cannot: transaction propagation, SQL
correctness, optimistic locking under six concurrent writers, and `REQUIRES_NEW` surviving a
rollback. **Three of the seven bugs in §9 were found here.**

### System — black box over HTTP

```java
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",         // no OpenAI beans at all — cannot
        "spring.ai.model.embedding=none",    // reach the network by accident
        "app.ai.mode=offline",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
```

Nothing is injected but the port, a REST client and the scripted model. Assertions go
through the API a customer would use; the only shortcut is reading the database afterwards
to confirm what the API *claimed* happened actually happened.

### `ScriptedChatModel` — the one seam

```java
@Override
public ChatResponse call(Prompt prompt) {
    Prompt current = withToolCallbacks(prompt);
    for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
        Step step = steps.poll();
        if (step == null) throw new IllegalStateException("ScriptedChatModel ran out of steps ...");
        ChatResponse response = step.respond(current);
        if (!response.hasToolCalls()) return response;

        // The framework's own tool execution: schema binding, ToolContext
        // propagation and result serialisation all happen for real here.
        ToolExecutionResult execution = toolCallingManager.executeToolCalls(current, response);
        current = new Prompt(execution.conversationHistory(), current.getOptions());
    }
    throw new IllegalStateException("Tool loop did not terminate ...");
}
```

Used like:

```java
chatModel.script(
        toolCall("cancelOrder", "{\"orderNumber\":\"ORD-1001\",\"reason\":\"ordered by mistake\"}"),
        reply("I've prepared the cancellation of ORD-1001 -- confirm and I'll put it through."));
```

> **Interviewer: "Why not test against a real model?"**
> It buys nothing and costs a great deal: the assertion becomes non-deterministic, the run
> becomes billable, and CI depends on somebody else's uptime. What needs testing is the
> machinery *around* the model — schema generation, argument binding, the authorization
> checks in the tools, the audit rows, the confirmation gate — and all of that is exercised
> for real by scripting the one decision a model would make and letting Spring AI's actual
> `ToolCallingManager` do everything else.
>
> **What it does not test** is whether a real model picks the right tool. That is a
> prompt-evaluation question and belongs in an eval suite over a fixed set of conversations,
> not in CI. It is the honest gap in this project — see §11.3.

`invocationCount()` is what makes the guardrail tests meaningful:

```java
assertThat(response.read("$.modelInvoked", Boolean.class)).isFalse();
assertThat(chatModel.invocationCount()).isZero();   // no tokens spent on an attack
```

### Coverage — gated on the merge, not per suite

```xml
<execution><id>merge-exec-files</id> ... </execution>   <!-- jacoco-*.exec, minus its own output -->
<execution><id>coverage-gate</id>
    <configuration><rules><rule><element>BUNDLE</element><limits>
        <limit><counter>LINE</counter><minimum>0.85</minimum></limit>
        <limit><counter>BRANCH</counter><minimum>0.75</minimum></limit>
    </limits></rule></rules></configuration>
</execution>
```

Gating each suite separately would penalise the transaction-propagation and SQL paths only
an integration test can reach — the wrong incentive. Actual: **93.4% line, 85.4% branch.**

---

## 8. CI/CD

### `ci.yml`

```
build ──┬── unit-tests ────────┐
        ├── integration-tests ─┼── coverage (merged gate)
        └── system-tests ──────┤
                               └── container (build image, boot it, scan it)
```

The container job is the one that earns its minutes — it boots the built image against a
real pgvector database and asserts:

```bash
health: UP                        # it starts and migrates from inside the jar
unauthenticated request: 401      # the filter chain works in the packaged artifact
authenticated order list: ok      # migrations AND the seed ran in the container
policy retrieval: ok              # pgvector + the vector migration + startup ingestion line up
```

**An image build that only proves the Dockerfile parses is not worth the minutes.** This
step found three defects (§9.6, §9.7 and the Spring Security default user).

Also in CI: dependency review on PRs, Trivy image scanning (reported, not gated — a base
image CVE with no fix should not turn `main` red overnight), and per-suite summaries written
into `$GITHUB_STEP_SUMMARY` by two hand-written scripts rather than marketplace actions
(twenty lines, no supply-chain surface).

### `cd.yml`

Chained on CI success via `workflow_run` rather than duplicating the test jobs, **so what
ships is what the tests passed against**. Publishes to GHCR with provenance and SBOM, then:

```yaml
- name: Stand up the published image and verify it
  run: |
    IMAGE="${{ needs.publish.outputs.image }}@${{ needs.publish.outputs.digest }}"
    docker pull "$IMAGE"
    .github/scripts/container-smoke-test.sh "$IMAGE"
```

**Deployed by digest, not tag** — a digest is immutable, so what gets verified is
byte-for-byte what the publish job built, even if a tag moves in between.

Production is gated behind a GitHub environment with a required reviewer. That is the same
shape as the confirmation gate inside the application: *the automated system can prepare a
change and cannot apply one.* Worth saying out loud in an interview — it shows the principle
was a principle, not a one-off.

---
## 9. Every defect found, and how

Seven real bugs, each caught by a different layer. **This section is the most valuable thing
in the document for an interview** — anyone can describe an architecture, but describing a
bug you found, why the cheaper test missed it, and what you changed shows you actually built
the thing.

### 9.1 The confirmation handler rolled back its own error state *(integration)*

**Symptom.** A test approved a proposal whose action had become illegal in the meantime, and
asserted the proposal ended as `FAILED`. It was `AWAITING_CONFIRMATION`.

**Cause.** `decide()` was `@Transactional`. On the error path it marked the row `FAILED`,
then rethrew — and the rethrow rolled back the transaction *including that mark*.

**Consequence had it shipped.** A proposal permanently awaiting a confirmation that could
never succeed. Every retry re-runs the executor, fails again, and rolls back again. The row
looks untouched forever.

**Fix.** `decide()` is a saga, not a transaction — claim, execute, record, each committing
independently. The action itself stays atomic inside `OrderService.cancel()`.

**Why no unit test could have caught it.** Rollback is a property of a real transaction
manager. With a mocked repository, `transition(..., FAILED, ...)` is just a recorded
invocation — it "succeeds" and the assertion passes. **This is the canonical example of what
integration tests are for**, and it is the answer to "why not just mock the database?"

### 9.2 A misleading refusal about an order the customer was holding *(integration)*

**Symptom.** Second return request on an order answered `ORDER_NOT_DELIVERED`.

**Cause.** Opening a return moves the order to `RETURN_REQUESTED`. Eligibility checked
delivery state *before* the open-RMA rule, so the status check fired first.

**Consequence.** The bot tells a customer holding a delivered parcel that it "is not
delivered yet (status RETURN_REQUESTED)" — visibly wrong, and it destroys trust in
everything else the assistant says.

**Fix.** Check the open RMA first; add an explicit `ALREADY_RETURNED` branch.

**The lesson worth articulating:** validation order is part of the user experience, not just
control flow. When several rules can reject the same request, the one that fires must be the
*most informative* one, not the first one you happened to write.

### 9.3 An off-by-one that would have shown up as a customer complaint *(integration)*

**Symptom.** A parcel delivered 45 days ago was reported as 44.

**Cause.** `Duration.between(deliveredAt, now).toDays()` truncates. 45 days minus two
minutes is 44.

**Fix.**

```java
long daysSinceDelivery = ChronoUnit.DAYS.between(
        LocalDate.ofInstant(shipment.deliveredAt(), ZoneOffset.UTC), LocalDate.now(clock));
```

**Why it matters beyond the number.** The policy says the window runs "from the *date* the
carrier marks the parcel delivered". Elapsed-duration arithmetic answers a different question
from the one the policy asks, and on the boundary day the customer is told a smaller number
than reality. Whenever the domain speaks in calendar days, the code should too.

### 9.4 One similarity threshold for two different embedding models *(system)*

**Symptom.** Every policy question answered "I don't have that policy to hand." Zero
citations. Retrieval returned nothing.

**Cause.** `similarity-threshold: 0.35` — a sensible default for dense hosted embeddings —
applied to the offline model, whose scores live on a completely different scale.

**The measurement that settled it:**

| Query | Top score |
| --- | --- |
| "how many days do I have to return a delivered item" | 0.381 |
| "tracking says delivered but the parcel is lost" | 0.431 |
| "how long until my refund settles" | 0.325 |
| "cancel my order before it ships" | **0.261** |
| "what's the weather in Austin tomorrow" | 0.095 |
| "who won the world cup in 1998" | 0.071 |

Relevant: 0.26–0.43. Irrelevant: 0.07–0.16. A threshold of 0.35 was discarding real matches;
0.20 separates them with room on both sides.

**Fix.** The threshold travels with the model, and the measurements are recorded in the code
next to it.

**The generalisable lesson:** *a similarity threshold is a property of the embedding model,
not of the application.* Carrying one number across a model change is how a RAG system
quietly stops retrieving — quietly, because there is no error, just an assistant that has
become unhelpful.

### 9.5 The scope guard refused an ordinary follow-up *(system)*

**Symptom.** "and when will it get here?" blocked as out of scope, mid-conversation.

**Cause.** The guard judged each message in isolation. That sentence has no order number, no
domain word, and matches no passage.

**Fix.** Follow-up turns get the benefit of the doubt; explicit off-topic patterns still
apply at any depth, so a hard turn onto the weather is still refused.

**What it illustrates:** a stateless filter over a stateful conversation will always produce
absurd refusals, because natural dialogue is full of anaphora. Guards need the same context
the model gets.

### 9.6 The application refused to start without a chat model *(container)*

**Symptom.** The container smoke test's very first run: `APPLICATION FAILED TO START —
Parameter 1 of method chatClientBuilder required a bean of type 'ChatModel' that could not
be found.`

**Cause.** `ChatClient` was a hard constructor dependency of `SupportAgent`.

**Consequence.** A missing or expired `OPENAI_API_KEY` in production takes down order
lookups, returns, refunds and policy search — none of which need a model. A degraded
dependency becomes a total outage.

**Fix.**

```java
this.chatClient = chatModels.getIfAvailable() != null ? builders.getObject().build() : null;
```

Chat answers 503; everything else keeps working. `NoChatModelSystemIT` pins it.

**A subtlety worth mentioning if pressed:** the first fix asked `ObjectProvider` for the
*builder*, which failed the same way — the builder's bean definition always exists, and
requesting it when no model is configured propagates the creation failure rather than
returning null. Keying off the `ChatModel` is what actually works.

### 9.7 `-DskipTests` was silently ignored *(container)*

The Dockerfile ran `mvn package -DskipTests` and the tests ran anyway. Naming `skipTests`
directly in the surefire `<configuration>` **takes precedence over the user property**. Fix:
derive the per-suite switches from it.

```xml
<skipTests>false</skipTests>
<skip.unit.tests>${skipTests}</skip.unit.tests>
<skip.integration.tests>${skipTests}</skip.integration.tests>
```

Small, but a good "surprising thing I learned" answer: Maven plugin configuration beats
user properties, which is the opposite of most people's mental model.

### 9.8 Two CI defects worth mentioning

- **Artifact filename collision.** The integration and system jobs both uploaded failsafe's
  default `jacoco-it.exec`. Downloading both into one directory collided and
  `jacoco:merge` failed on a corrupt file. Each suite now names its file after itself, and
  the coverage job asserts three files arrived — *a silent miss would have surfaced as a
  coverage drop and been blamed on the tests.*
- **Docker repository names must be lowercase.** `github.repository` preserves the owner's
  capitalisation. `docker/metadata-action` normalises it for generated tags, which hid the
  problem until a job composed a pull reference by hand.

---

## 10. Choosing a local model — measured, not assumed

The project runs fully local via an `ollama` profile. Picking the model produced the most
interesting empirical result in the whole build.

`scripts/probe-ollama-model.py` loads the **real** system prompt and the **real** five-tool
set from the repository and checks three things. Results on an M2 Pro / 16 GB:

| Model | Read tools | Action tools | Overclaims? | Verdict |
| --- | --- | --- | --- | --- |
| `llama3.1:8b` | 4/4 | **4/4** | no | Ships with the profile |
| `qwen2.5:7b` | 3/3 | **0/3** | no | Reads only |
| `mistral:7b` | 0/3 | 0/3 | no | Unusable |

**`mistral` is the trap.** `ollama show mistral` lists `tools` as a capability. It never
emits a tool call — it writes *"you can use the `lookupOrder` function"* instead. An agent
built on it answers fluently and never touches an order, and **the failure is invisible
unless you check whether a tool actually ran.**

**`qwen2.5:7b` is the more interesting result** — a *selection* ceiling, not a capability
one. Bisecting the tool set, same prompt, same message, only the number of alternatives
changing:

| Tools offered | `cancelOrder` called |
| --- | --- |
| 1–3 | 4/4 |
| 4 | 1/4 |
| 5 | 0/4 |

I diagnosed it wrong first. My hypothesis was the prompt — specifically a section headed
*"What you cannot do"* beginning *"You cannot cancel an order or open a return yourself"*,
which reads as a prohibition rather than a description of the tool's semantics. Rewriting it
moved the number barely at all, and the **full prompt with three tools still scored 4/4**,
which killed the hypothesis. It is capability: a 7B model cannot discriminate between five
tools that share a signature.

**And reputation would have picked wrong.** Qwen2.5 generally outscores Llama 3.1 on
function-calling benchmarks, and on *this* five-tool set it fails where Llama 3.1 succeeds.
Benchmarks measure a different distribution from your tools. That is the entire argument for
the probe script.

**The design paid off here too.** When qwen2.5 narrated a cancellation it never requested,
the response still said `pendingAction: null` and `toolCalls: []`, because those are read
back out of the audit table (§5.8). The prose lied; the structured data did not; no confirm
button rendered. A failure mode became visible instead of silent.

---
## 11. Interview questions

### 11.1 "What was the hardest bug?"

**Use §9.1 — the confirmation handler rolling back its own error state.** It is the best
answer available because it is subtle, it has a real consequence, the fix is architectural
rather than a patch, and it demonstrates *why* you built the test layer you built.

**How to tell it (about 90 seconds):**

> The confirmation handler is the piece that turns an approved proposal into a real
> cancellation. I'd written it as one `@Transactional` method: check ownership, claim the
> token, execute, record the result. Clean, obvious, and wrong.
>
> An integration test approved a proposal whose order had been cancelled through a different
> route in the meantime. I expected the proposal to end up `FAILED`. It came back
> `AWAITING_CONFIRMATION`. The catch block *was* marking it `FAILED` — and then rethrowing,
> and the rethrow rolled back the transaction including that mark. My error handling was
> being undone by the exception it was handling.
>
> The consequence in production would have been a proposal permanently awaiting a
> confirmation that could never succeed, retrying and rolling back forever, looking untouched
> the whole time.
>
> The fix was to stop treating it as one transaction. It's a three-step saga — claim,
> execute, record — and each step has to commit on its own. The action itself is still atomic
> because `OrderService.cancel()` opens its own transaction; what isn't atomic is the
> bookkeeping around it. And that turned out to matter twice, because if the claim is inside
> the same transaction as the work, it's invisible to a concurrent request until commit —
> which is the opposite of what claiming a token is for.
>
> What I took from it is why the test layer earned its cost. No unit test could have caught
> it: with a mocked repository the `FAILED` transition is just a recorded invocation, so it
> "succeeds" and the assertion passes. Rollback is a property of a real transaction manager,
> so only a real database could show it. Three of my seven bugs came from that suite.

**If they push — "couldn't you have kept one transaction and used a savepoint?"**
Yes, `Propagation.NESTED` with a savepoint would preserve the `FAILED` write. I didn't
because it keeps the claim inside the outer transaction, which still hides it from a
concurrent confirmation. The saga fixes both problems; the savepoint fixes one.

**Runner-up bug** (if they want a second, or a non-Spring one): §9.4, the similarity
threshold. Good because it is silent — no exception, no error, just a RAG system that has
quietly stopped retrieving — and the fix came from *measuring the score distribution* rather
than guessing.

---

### 11.2 "What was the hardest decision?"

**Use: whether state-changing tools should execute or propose.** It's the decision the whole
project is organised around.

> The obvious design is to let `cancelOrder` cancel the order. The tool has the customer's
> identity, the service enforces every rule, and authorization is airtight — so on paper
> there's nothing unsafe about it. That's what made it a real decision rather than an obvious
> one.
>
> What changed my mind is that authorization and intent are different problems. The service
> layer can prove the *customer is allowed* to cancel that order. It cannot prove the
> *customer asked* to. Between those two sits a language model interpreting free text, and
> the failure mode isn't a security breach, it's a misunderstanding: the customer says
> "should I cancel this?" and the model hears "cancel this". No amount of authorization
> catches that, because the request is genuinely authorized.
>
> So the tools propose and a human disposes. The cost is real — an extra round trip, a
> state machine, expiry, replay protection, and the model sometimes needing to be told not to
> claim it's done. The benefit is that the worst outcome of a perfectly successful prompt
> injection is a row in a table that nobody approves.
>
> The part I'd defend hardest is that I *didn't* put the gate in the service layer. The REST
> API cancels directly with no token, because a first-party client has already shown the
> customer a confirmation dialog. Domain rules apply to everyone; human-in-the-loop applies
> to the caller that can't be trusted to have asked. If I'd pushed the gate down into
> `OrderService` it would have been the wrong abstraction — I'd have been encoding a fact
> about one *caller* as a fact about the *domain*.

**Second decision, if they want another: JDBC over JPA.** Frame it as a trade you made
consciously — `@Version` would have given optimistic locking for free, and I gave that up to
keep the SQL visible, because the project's whole claim is that you can see exactly what the
model can cause. Naming what you gave up is what makes it read as a decision rather than a
preference.

---

### 11.3 "What would you do differently?"

Have four ready. Lead with the first — it is the most substantive and the most honest.

**1. Build the eval suite. This is the real gap.**

> There's no test that a *real* model picks the right tool. My system suite scripts the
> model's decisions, which is right for CI — deterministic, free, no external dependency —
> but it means I'm testing my machinery, not the agent's judgement. I found out how big that
> gap is when I benchmarked local models: one advertised tool support and never emitted a
> tool call, and another handled reads perfectly but couldn't pick `cancelOrder` out of five
> similar signatures. Neither of those would have failed a single one of my 215 tests.
>
> What I'd build is a fixed set of maybe fifty conversations with known-correct tool
> trajectories, run nightly against the real model, scoring tool-selection accuracy,
> argument correctness, citation validity and overclaim rate. Then a model or prompt change
> is a number that moved rather than a vibe. My `probe-ollama-model.py` is a four-scenario
> prototype of exactly that — I'd grow it into the real thing.

**2. Reconsider the tool granularity, given what the benchmarks showed.**

> Five tools that all take an `orderNumber` is a hard discrimination problem, and I have the
> data showing a 7B model degrades from 4/4 to 0/4 as you add the fourth and fifth. I'd
> explore either merging the read tools into one `getOrder(orderNumber, aspect)` — trading
> schema clarity for fewer alternatives — or a two-stage router. I'd want the eval suite
> first, though, because right now I'd be guessing at whether it helps, and the strong model
> doesn't need it.

**3. Structured output for the citation contract.**

> Citations currently work by asking the model to write `[1]` markers and parsing them back
> out. It's checkable, which is the important part, but it's still prose parsing. Spring AI
> supports structured output; I'd have the model return `{answer, citedPassages[]}` so the
> contract is a schema rather than a regex, and the "fabricated marker" case becomes a
> validation failure instead of a silent drop.

**4. Streaming, and the identity check that would need.**

> Everything is blocking `call()`. For a chat UI you want tokens as they arrive. The
> reason I didn't is that `SecurityContextHolder` is a `ThreadLocal`, and the moment the tool
> loop leaves the request thread that identity check breaks — silently, which is the worst
> way. I already assert the tool context and the security context agree, so the failure would
> be loud rather than silent, but I'd want to move identity fully onto the `ToolContext` and
> stop depending on thread affinity before shipping streaming.

**Smaller ones**, if the conversation stays on it: conversation history is unbounded and
would need summarising or expiry; there's no rate limiting per customer; `pending_actions`
has no reaper for expired rows; `ChatController`'s audit endpoint filters in Java rather than
SQL, which is fine at this size and wrong at scale.

---

### 11.4 The likely technical questions, with short answers

**On the guardrail**

- *"How do you stop the AI doing something it shouldn't?"* — Three structural properties, not
  a prompt: tools take no identity parameter so it can't appear in the schema; every tool
  calls a service layer that authorizes independently; state-changing tools write a proposal
  and return a token that only an authenticated HTTP request can redeem. §5.6.
- *"What if the model is jailbroken?"* — Its best outcome is a row in `pending_actions` that
  nobody approves. The guards are a first line that saves tokens; the structure is the
  defence.
- *"What if someone steals a confirmation token?"* — It's bound to the customer in the row,
  checked against the authenticated principal, single-use by compare-and-set, and expires in
  10 minutes. A different customer holding it gets 404.
- *"Double-click on Confirm?"* — CAS `AWAITING_CONFIRMATION → EXECUTING`; the loser matches
  zero rows and gets 409. Tested with six concurrent approvals.
- *"What if the order changes between proposal and approval?"* — Eligibility is re-checked
  inside the execution transaction. The proposal goes `FAILED`; the order is untouched.

**On RAG**

- *"How do you chunk?"* — Section headings, because the corpus is one-question-per-heading
  and a chunk starting mid-rule cites badly. 1200-char paragraph-aligned fallback with
  150-char overlap so nothing is unbounded.
- *"How do you know the citation is real?"* — Markers are resolved against the passages
  actually supplied; an out-of-range marker resolves to nothing. The response reports both
  what was cited and everything retrieved.
- *"Why pgvector over a dedicated vector DB?"* — One store, one failure mode, transactional
  consistency with the domain. At 22 chunks it is not the bottleneck. At scale I'd revisit.
- *"How do you avoid re-embedding on every restart?"* — Per-document SHA-256 in the chunk
  metadata; unchanged documents are skipped; a changed one has its old revision deleted
  first so removed sections stop being retrievable.
- *"What's your chunk id strategy?"* — Deterministic UUIDv3 from (policy, section, index), so
  `ON CONFLICT DO UPDATE` makes re-ingestion an upsert. Random ids would double the corpus
  every restart.

**On Spring / architecture**

- *"Why `REQUIRES_NEW` on the audit write, and why a separate bean?"* — So the row survives
  the rollback of what it records; separate bean because self-invocation bypasses the proxy.
  §5.8.
- *"Why 404 instead of 403?"* — Non-enumeration. §5.4.
- *"How do you handle concurrency?"* — Optimistic locking via a version column and a
  compare-and-set `UPDATE`; rows-affected ≠ 1 becomes an exception. Six-thread test.
- *"Why JDBC over JPA?"* — Explicitness; the SQL is the thing being reasoned about. I gave
  up `@Version` for free and wrote the CAS by hand.
- *"Why is `decide()` not transactional?"* — §5.7. This is the best question in the project.

**On testing**

- *"What does each suite prove?"* — Unit: rules and boundaries, fast. Integration:
  transaction propagation, SQL, concurrency — three of seven bugs. System: contracts over
  HTTP through the real filter chain.
- *"Why not test against a real LLM?"* — Non-deterministic, billable, depends on someone
  else's uptime; and the machinery is what needs testing. The gap is real and named — §11.3.
- *"85% coverage — is that meaningful?"* — It's a floor against regression, not a goal. The
  gate is on merged data across all three suites so integration-only paths aren't penalised.
  What I'd actually point at is the specific assertions: `verify(orderService, never())
  .cancel(...)` is worth more than any percentage.

**Curveballs**

- *"What's the biggest weakness?"* — No eval suite. Say it before they find it.
- *"What would break first at 100× scale?"* — HNSW index build time and memory on a much
  larger corpus, and the audit table needing partitioning by month. The chat path is
  latency-bound on the model, not on us.
- *"How much did this cost to run?"* — Nearly nothing: all 215 tests and the whole CI
  pipeline run with no model provider configured at all.

---

## 12. Numbers to have ready

| | |
| --- | --- |
| Tests | 215 — 138 unit, 37 integration, 40 system |
| Coverage | 93.4% line / 85.4% branch; gate at 85 / 75 |
| Main classes / test classes | 88 / 34 |
| Tools | 5 — 3 read, 2 proposing |
| Policy corpus | 4 documents → 22 chunks |
| Embedding dimensions | 1536 (`text-embedding-3-small` and the offline model both) |
| Similarity threshold | 0.35 hosted / 0.20 offline (measured) |
| Return window | 30 days standard, 60 furniture |
| Confirmation TTL | 10 minutes |
| Concurrency tests | 6 threads, cancellation and confirmation |
| Bugs found | 7 — 3 integration, 2 system, 2 container |
| CI jobs | 7, with a merged coverage gate |

**The three sentences to have word-perfect:**

1. *"The model never touches the database — it calls a service layer that authorizes every
   request, exactly like any other client."*
2. *"State-changing tools don't change state; they write a proposal and return a token that
   only an authenticated human request can redeem."*
3. *"The tool list on a chat response is read back out of the audit table, not accumulated in
   memory, so what the caller is shown and what was durably recorded cannot disagree."*
