-- ---------------------------------------------------------------------------
-- Policy embeddings. The table shape matches what Spring AI's PgVectorStore
-- expects, but we own it in Flyway rather than letting the library create it:
-- schema changes then go through the same review and rollback path as the rest
-- of the database, and `initialize-schema` stays false in every environment.
--
-- vector(1536) matches OpenAI text-embedding-3-small and the offline
-- deterministic embedding model used in CI, so one schema serves both.
-- ---------------------------------------------------------------------------

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE vector_store (
    id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    content   text,
    metadata  json,
    embedding vector(1536)
);

-- HNSW with cosine distance: PgVectorStore's COSINE_DISTANCE default issues
-- `embedding <=> ?`, which only uses an index built with vector_cosine_ops.
CREATE INDEX idx_vector_store_embedding
    ON vector_store USING hnsw (embedding vector_cosine_ops);
