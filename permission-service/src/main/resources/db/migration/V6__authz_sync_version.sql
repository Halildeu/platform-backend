-- P0: authz_version mechanism for permission cache invalidation
-- Consultation: CNS-20260410-001 (Claude + Codex consensus)
-- Single-row version counter for cache key generation

CREATE TABLE IF NOT EXISTS authz_sync_version (
    id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO authz_sync_version (id, version, updated_at)
VALUES (1, 1, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;
