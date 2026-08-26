# Order Support Assistant

An e-commerce customer support agent built with **Spring Boot 3.5** and **Spring AI 1.1**:
a chat API where a language model answers policy questions from a retrieved corpus and
takes action on real orders — without ever being able to change one on its own.

The interesting part is not that the model can call tools. It is what happens at the
boundary when it does.

```
                 ┌──────────────────────────────────────────────────────────┐
   POST /chat ──►│ API key filter → CustomerPrincipal in the SecurityContext│
                 └──────────────────────────────┬───────────────────────────┘
                                                ▼
                     ┌──────────────────────────────────────────────┐
                     │ SupportAgent                                 │
                     │   1. authorize the conversation              │
                     │   2. prompt-injection guard  ─┐              │
                     │   3. retrieve policy passages │ before the   │
                     │   4. scope guard             ─┘ model runs   │
                     │   5. model + tools                           │
                     │   6. extract citations                       │
                     │   7. read the audit trail back               │
                     └───────────────┬──────────────────────────────┘
                                     ▼
   ┌─────────────────────────────────────────────────────────────────────────┐
   │ SupportTools    lookupOrder · trackDelivery · checkRefundStatus          │
   │                 cancelOrder · initiateReturn                            │
   │                                                                         │
   │   no customer id parameter · no database access · no state changes      │
   └───────────────┬──────────────────────────────────────┬──────────────────┘
                   ▼                                      ▼
   ┌───────────────────────────────┐      ┌──────────────────────────────────┐
   │ OrderService / ReturnService  │      │ pending_actions                  │
   │ RefundService                 │      │  a proposal, awaiting a human    │
   │  · ownership check            │      └───────────────┬──────────────────┘
   │  · eligibility rules          │                      │
   │  · optimistic locking         │      POST /chat/confirmations/{token}   │
   └───────────────┬───────────────┘                      │  (authenticated) │
                   │                                      ▼                  │
                   └──────────────◄───────────── ActionExecutor ─────────────┘
                                     ▲
   GET/POST /api/v1/orders ──────────┘   the same door, for the mobile app
```

---

## The three things this demonstrates

### 1. Tool calling against a governed service layer

Five tools sit in [`SupportTools`](src/main/java/com/acme/ordersupport/agent/SupportTools.java).
Three properties hold for every one of them, and together they are the answer to *"how do
you stop the AI doing something it shouldn't?"*:

**No database.** Not a repository, not a connection, not a SQL string. Every tool calls the
same service method the REST controllers call, so validation, authorization and optimistic
locking apply identically whether the caller is a language model or the mobile app.
[`OrderController`](src/main/java/com/acme/ordersupport/web/OrderController.java) exists to
make that checkable rather than merely claimed — and
[`RestApiSystemIT`](src/test/java/com/acme/ordersupport/system/RestApiSystemIT.java) asserts
both callers get the same refusals.

**No identity parameter.** No tool accepts a customer id, so none appears in the JSON schema
the model is given, so there is nothing for it to hallucinate or be talked into. The acting
customer comes from the authenticated security context, cross-checked against the tool
context. One `requireOwned()` in
[`OrderService`](src/main/java/com/acme/ordersupport/order/OrderService.java) is the choke
point every read and write passes through.

**No state changes.** See below.

### 2. RAG over policy documents with pgvector

Four policy documents under [`resources/policies`](src/main/resources/policies) — returns,
refunds, shipping, cancellation — chunked on section headings, embedded, and stored in a
`vector_store` table that **Flyway owns** rather than the library creating it, so schema
changes go through the same review path as any other DDL.

Chunk ids are derived from `(policy, section, index)`, so re-ingestion upserts instead of
duplicating. Each document carries a hash of its source text, so a restart re-embeds nothing
and an edit re-embeds one document.

Citations are checkable rather than stylistic. Retrieved passages go into the prompt
numbered `[1]`, `[2]`; the model is told to mark each claim with its number; and
[`CitationExtractor`](src/main/java/com/acme/ordersupport/agent/CitationExtractor.java)
resolves the markers back against the passages that were actually supplied. A fabricated
`[7]` against four passages resolves to nothing. The response reports **both** what was
cited and everything that was retrieved — the gap between those two numbers is the useful
signal when tuning retrieval.

### 3. Guardrails you can demo

**Any state-changing tool returns a proposal, not a change.** `cancelOrder` does not cancel.
It validates against the same rules the execution would use, writes a row to
`pending_actions`, and returns a confirmation token. The only path from there to a real
change is a second authenticated HTTP request from the customer who owns the token —
which the model cannot make, because it is HTTP and not a tool. **The best outcome available
to a perfectly jailbroken prompt is a row in a table.**

The token is claimed by compare-and-set before any work starts, so a double-clicked Confirm
button cancels one order rather than two. It is bound to its customer, expires, and cannot
be replayed.

**Out-of-scope questions and injection attempts never reach the model.** Both guards run
before the completion, so a blocked message costs one vector search and no tokens. The
system tests assert `modelInvoked == false`, not merely that the reply was a refusal.

**Every tool call is logged, including the ones that were denied.** `tool_invocations` is
append-only and written in `REQUIRES_NEW`, so an audit row survives the rollback of the
transaction it was recording — the gaps in an audit trail are exactly where the interesting
failures are. The tool list on a chat response is **read back out of that table** rather than
accumulated in memory, so what a caller is shown and what was durably recorded cannot
disagree.

---

## Running it

Needs JDK 21 and Docker.

```bash
make db        # Postgres + pgvector
make run       # the application, local profile, demo fixtures loaded
make demo      # walks the guardrail story end to end
```

Chat needs a model provider; everything else does not:

```bash
export OPENAI_API_KEY=sk-...
make run
```

Without a key the application still starts and serves order lookups, returns, refunds and
policy search — only `POST /api/v1/chat` answers 503. That is deliberate, and
[`NoChatModelSystemIT`](src/test/java/com/acme/ordersupport/system/NoChatModelSystemIT.java)
pins it: one unconfigured optional dependency should cost one endpoint, not the service.

Two demo customers are seeded, with API keys `osa_demo_ada_key` and `osa_demo_blake_key`.
The second exists to make cross-customer authorization testable.

```bash
curl -s localhost:8080/api/v1/orders -H 'X-API-Key: osa_demo_ada_key'
curl -s 'localhost:8080/api/v1/policies/search?q=how+many+days+to+return+an+item' \
     -H 'X-API-Key: osa_demo_ada_key'
curl -s -X POST localhost:8080/api/v1/chat -H 'X-API-Key: osa_demo_ada_key' \
     -H 'Content-Type: application/json' \
     -d '{"message":"Please cancel ORD-1001, I ordered it by mistake"}'
```

### Fixtures

Each seeded order exercises one branch of the rules. `ORD-1004` and `ORD-1007` were
delivered on the same day and differ only in category, so the pair demonstrates that the
extended furniture window is the only thing separating them.

| Order | State | Exercises |
| --- | --- | --- |
| ORD-1001 | PROCESSING | cancellable |
| ORD-1002 | SHIPPED | not cancellable, not yet returnable |
| ORD-1003 | DELIVERED, 5 days | inside the return window |
| ORD-1004 | DELIVERED, 45 days, furniture | inside the extended 60-day window |
| ORD-1007 | DELIVERED, 45 days, audio | outside the standard 30-day window |
| ORD-1005 | DELIVERED, gift card | nothing returnable on the order |
| ORD-1006 | CANCELLED | refund in flight |
| ORD-2001 | Blake's | cross-customer authorization |

---

## Tests

215 tests in three suites, each runnable alone so a failure names its own layer.

```bash
make unit          # 138 tests, no Docker, seconds
make integration   # 37 tests, Testcontainers pgvector
make system        # 40 tests, the app on a real port over HTTP
make verify        # all three, plus the merged coverage gate
```

**Unit** — rules, guards and the tool boundary, with mocks and a fixed clock. Return windows
and token expiry are asserted by moving the clock, not by sleeping.

**Integration** — one Testcontainers pgvector database for the whole run. Real migrations,
real embeddings, real HNSW cosine ranking, real optimistic locking asserted under six
concurrent writers.

**System** — the application on a random port, driven through its own security filter chain.
The only seam is [`ScriptedChatModel`](src/test/java/com/acme/ordersupport/testsupport/ScriptedChatModel.java),
which scripts the one decision a real model would make and lets Spring AI's real
`ToolCallingManager` do everything else: schema generation, argument binding, tool context
propagation, result serialisation. Calling a hosted model from CI would buy nothing and cost
determinism, money and somebody else's uptime.

What that does **not** test is whether a real model picks the right tool. That is a prompt
evaluation question and belongs in an eval suite over a fixed set of conversations, not in CI.

Coverage is gated at 85% line / 75% branch over the **merged** data from all three suites.
Gating each suite separately would penalise the transaction-propagation and SQL paths that
only an integration test can reach, which is the wrong incentive.

### What the suites caught

Worth recording, because it is the argument for having all three:

| Found by | Defect |
| --- | --- |
| Integration | `ConfirmationService.decide()` was one transaction, so marking a proposal FAILED and rethrowing rolled the mark back and left it awaiting a confirmation that could never succeed. It is a saga — claim, execute, record — and each step must commit on its own. |
| Integration | Return eligibility checked delivery state before the open-RMA rule, so a second return request was answered with "this order is not delivered yet" while naming status `RETURN_REQUESTED`, about a parcel the customer was holding. |
| Integration | The return window used `Duration.toDays()`, which truncates: a parcel delivered 45 days and two minutes ago was reported as 44. The policy measures from the delivery *date*. |
| System | One similarity threshold was applied to both embedding models. 0.35 suits dense hosted embeddings; the local model scores relevant passages 0.26–0.43 and irrelevant ones 0.07–0.16, so real matches were being discarded and the bot answered every policy question with "I don't have that policy to hand". |
| System | The scope guard judged each message alone, so "and when will it get here?" was refused as off-topic mid-conversation. |
| Container | The application refused to boot without a chat model, taking order lookups and policy search down with a missing API key. |
| Container | `-DskipTests` was silently ignored, because naming `skipTests` in the surefire configuration overrides the user property. |

---

## CI/CD

[`ci.yml`](.github/workflows/ci.yml) — build, then the three suites in parallel, then a single
coverage gate over their merged execution data, then a container job that **boots the built
image against a real database** and checks health, rejection of an unauthenticated call, the
seeded data, and policy retrieval. That last job is the one that found the three container-row
defects above; an image build that only proves the Dockerfile parses is not worth its minutes.

[`cd.yml`](.github/workflows/cd.yml) — chains on CI success rather than duplicating the test
jobs, so what ships is what the tests passed against. Publishes to GHCR with provenance and an
SBOM, stands the published image up and verifies it, and gates production behind a GitHub
environment with a required reviewer — the same shape as the confirmation gate in the
application itself: the automated system can prepare a change and cannot apply one.

There is no hosted environment behind this repository. The production job says so rather than
echoing "deploying…" and proving nothing; everything gating it is real.

---

## Design notes

**Why `requireOwned` returns not-found for another customer's order.** A 403 confirms the
order number exists, which turns a chat box into an order-number oracle. The real reason is
logged, where it is useful and not exploitable.
[`SecuritySystemIT`](src/test/java/com/acme/ordersupport/system/SecuritySystemIT.java) asserts
the two responses are byte-identical apart from the number the caller supplied.

**Why the REST API has no confirmation gate.** Domain rules apply to everyone; human-in-the-loop
applies to the caller that cannot be trusted to have asked. A first-party client has already
shown the customer its own confirmation dialog.

**Why tools return refusals as values rather than throwing.** A business refusal is an ordinary
outcome the model needs to read and explain. Routing it through an exception makes the behaviour
depend on the framework's exception policy — whether the message reaches the model or the whole
turn fails. Exceptions stay for things that are genuinely broken.

**Why the policy constants live in code next to the corpus.** `CommercePolicy` holds the numbers
the service enforces and cross-references the sections the assistant quotes. If the two drift,
the bot starts promising things the backend will refuse — the failure mode a retrieval-grounded
support agent is most likely to hit in production.

**Why there is an offline embedding model in `src/main`.** So retrieval can be exercised for
real — real pgvector, real cosine ranking — with no API key, no network and no per-run cost. It
matches shared vocabulary, not meaning: "how long do I have to send something back" will not
find the returns section unless the words overlap. That is the trade, and it is the right one
for CI, where a flaky or billable assertion is worse than a narrow one.

## Limits worth naming

- **The pattern guards are a first line, not a solution.** Prompt-injection patterns are evaded
  by rephrasing, and anyone claiming otherwise is selling something. They buy a clean refusal, an
  audit row and no spent tokens. The actual defence is structural: tools with no identity
  parameter, a service layer that authorizes every call, and state changes that need a human. A
  prompt that defeats every pattern still cannot cancel an order. In production these would sit
  in front of a model-based classifier, not instead of one.
- **Tool execution assumes the request thread.** `ChatClient.call()` is blocking, so the security
  context follows the work. The tool context carries the customer id as well and the two are
  asserted equal, so a move to a reactive or pooled path fails loudly instead of silently acting
  on the wrong account.
- **No evaluation suite.** Whether a real model picks the right tool and grounds its answers is
  measured with an eval harness over fixed conversations. That is the obvious next piece.
- **Conversation history is unbounded per conversation** beyond the sliding window used for the
  prompt. A production system would summarise or expire it.
