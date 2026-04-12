-- ADR-0012 / Faz 4-a: Durable outbox for OpenFGA tuple sync.
-- Ensures role changes are propagated even if initial async handler fails.

CREATE TABLE IF NOT EXISTS tuple_sync_outbox (
    id          BIGSERIAL PRIMARY KEY,
    role_id     BIGINT NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts    INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    error_message TEXT,
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON tuple_sync_outbox (status, created_at);
