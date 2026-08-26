-- ---------------------------------------------------------------------------
-- Core commerce domain. Nothing in here knows the LLM exists: the assistant is
-- just one more client of the service layer that sits above these tables.
-- ---------------------------------------------------------------------------

CREATE TABLE customers (
    id           uuid        PRIMARY KEY,
    email        text        NOT NULL UNIQUE,
    full_name    text        NOT NULL,
    -- SHA-256 of the caller's API key. Stands in for whatever a real deployment
    -- would use (OIDC subject, session token); the point is that customer
    -- identity is established before the request ever reaches the agent.
    api_key_hash text        NOT NULL UNIQUE,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id               uuid          PRIMARY KEY,
    order_number     text          NOT NULL UNIQUE,
    customer_id      uuid          NOT NULL REFERENCES customers (id),
    status           text          NOT NULL,
    currency         char(3)       NOT NULL DEFAULT 'USD',
    total_amount     numeric(12,2) NOT NULL CHECK (total_amount >= 0),
    placed_at        timestamptz   NOT NULL,
    cancelled_at     timestamptz,
    shipping_city    text,
    shipping_country text,
    -- Optimistic lock: concurrent cancel/return attempts must not both win.
    version          integer       NOT NULL DEFAULT 0
);
CREATE INDEX idx_orders_customer ON orders (customer_id);

CREATE TABLE order_items (
    id          uuid          PRIMARY KEY,
    order_id    uuid          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    sku         text          NOT NULL,
    description text          NOT NULL,
    category    text          NOT NULL,
    quantity    integer       NOT NULL CHECK (quantity > 0),
    unit_price  numeric(12,2) NOT NULL CHECK (unit_price >= 0),
    returnable  boolean       NOT NULL DEFAULT true
);
CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE shipments (
    id                 uuid        PRIMARY KEY,
    order_id           uuid        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    carrier            text        NOT NULL,
    tracking_number    text        NOT NULL,
    status             text        NOT NULL,
    shipped_at         timestamptz,
    estimated_delivery date,
    delivered_at       timestamptz,
    last_location      text
);
CREATE INDEX idx_shipments_order ON shipments (order_id);

CREATE TABLE return_requests (
    id            uuid        PRIMARY KEY,
    rma_number    text        NOT NULL UNIQUE,
    order_id      uuid        NOT NULL REFERENCES orders (id),
    order_item_id uuid        REFERENCES order_items (id),
    status        text        NOT NULL,
    reason        text        NOT NULL,
    requested_at  timestamptz NOT NULL DEFAULT now(),
    resolved_at   timestamptz
);
CREATE INDEX idx_returns_order ON return_requests (order_id);

CREATE TABLE refunds (
    id                       uuid          PRIMARY KEY,
    order_id                 uuid          NOT NULL REFERENCES orders (id),
    return_request_id        uuid          REFERENCES return_requests (id),
    status                   text          NOT NULL,
    amount                   numeric(12,2) NOT NULL CHECK (amount >= 0),
    currency                 char(3)       NOT NULL DEFAULT 'USD',
    method                   text          NOT NULL,
    initiated_at             timestamptz   NOT NULL DEFAULT now(),
    settled_at               timestamptz,
    expected_settlement_date date,
    external_reference       text
);
CREATE INDEX idx_refunds_order ON refunds (order_id);

-- ---------------------------------------------------------------------------
-- Conversation state.
-- ---------------------------------------------------------------------------

CREATE TABLE conversations (
    id             uuid        PRIMARY KEY,
    customer_id    uuid        NOT NULL REFERENCES customers (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    last_active_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE chat_messages (
    id              bigserial   PRIMARY KEY,
    conversation_id uuid        NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    role            text        NOT NULL,
    content         text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_messages_conversation ON chat_messages (conversation_id, id);

-- ---------------------------------------------------------------------------
-- Guardrail state: the two tables that make "the model cannot act alone" real.
-- ---------------------------------------------------------------------------

-- A side effect the model has *proposed* but not performed. The row's id is the
-- confirmation token; only a human-authenticated HTTP call can redeem it.
CREATE TABLE pending_actions (
    id              uuid        PRIMARY KEY,
    conversation_id uuid        NOT NULL REFERENCES conversations (id),
    customer_id     uuid        NOT NULL REFERENCES customers (id),
    action_type     text        NOT NULL,
    tool_name       text        NOT NULL,
    order_id        uuid        NOT NULL REFERENCES orders (id),
    arguments       jsonb       NOT NULL,
    summary         text        NOT NULL,
    status          text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    decided_at      timestamptz,
    result          jsonb
);
CREATE INDEX idx_pending_actions_conversation ON pending_actions (conversation_id, created_at DESC);

-- Append-only audit trail. Every tool call is recorded here before the caller
-- sees a result, including the ones that were denied.
CREATE TABLE tool_invocations (
    id                bigserial   PRIMARY KEY,
    invocation_id     uuid        NOT NULL,
    conversation_id   uuid,
    customer_id       uuid,
    actor             text        NOT NULL,
    tool_name         text        NOT NULL,
    arguments         jsonb       NOT NULL,
    outcome           text        NOT NULL,
    detail            text,
    pending_action_id uuid,
    duration_ms       integer     NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_tool_invocations_conversation ON tool_invocations (conversation_id, id);
CREATE INDEX idx_tool_invocations_customer ON tool_invocations (customer_id, created_at DESC);

-- Prompts we refused to send to the model at all, and why.
CREATE TABLE guardrail_events (
    id              bigserial   PRIMARY KEY,
    conversation_id uuid,
    customer_id     uuid,
    guardrail       text        NOT NULL,
    verdict         text        NOT NULL,
    reason          text        NOT NULL,
    excerpt         text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_guardrail_events_conversation ON guardrail_events (conversation_id, id);
