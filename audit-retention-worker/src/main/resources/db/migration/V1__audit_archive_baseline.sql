-- Faz 24 KVKK audit pipeline (gitops#1250) — audit-retention-worker state schema.
--
-- ADR-0042 D4.3: the worker owns a SEPARATE schema `audit_archive` (same DB as
-- the consumer's `audit_event`) with its OWN Flyway history
-- (`audit_archive.audit_retention_flyway_history`). The worker has SELECT-only
-- on `audit_event.audit_event` (the immutable source) and is owner of
-- `audit_archive.*`. The consumer never writes here; clean ownership split.
--
-- These three tables drive a resumable, idempotent, fail-closed archival run:
--   * audit_archive_cursor       — singleton no-gap contiguous-prefix progress.
--   * audit_archive_ledger       — one immutable row per archived S3 object
--                                  (version-id pinned content+chain proof).
--   * audit_archive_tenant_anchor— authoritative per-tenant chain watermark
--                                  (PER_TENANT hash-chain continuity across
--                                  batch boundaries; ADR-0042 D4.4 RESOLVED).

-- ---------------------------------------------------------------------------
-- Singleton progress cursor. `last_archived_seq` is the high-water of the
-- contiguous, fully-eligible, fully-archived prefix of `audit_event.seq`. The
-- next run scans `seq > last_archived_seq ORDER BY seq ASC` and stops at the
-- first hot/ineligible row (never skips — ADR-0042 D4.2).
-- ---------------------------------------------------------------------------
CREATE TABLE audit_archive_cursor (
    id                 SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_archived_seq  BIGINT       NOT NULL DEFAULT 0,
    updated_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Seed the singleton row so the worker can FOR UPDATE lock it from run #1.
INSERT INTO audit_archive_cursor (id, last_archived_seq) VALUES (1, 0);

COMMENT ON TABLE audit_archive_cursor IS
    'Faz 24 gitops#1250: singleton no-gap contiguous-prefix archive cursor over audit_event.seq.';
COMMENT ON COLUMN audit_archive_cursor.last_archived_seq IS
    'High-water seq of the contiguous fully-archived prefix; next scan is seq > this.';

-- ---------------------------------------------------------------------------
-- Archive ledger — one row per successfully written + verified S3 segment
-- object. Immutable evidence record: content digests + S3 version ids +
-- retention + the per-object PER_TENANT chain anchors snapshot.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_archive_ledger (
    id                    BIGSERIAL    PRIMARY KEY,
    object_key            VARCHAR(512) NOT NULL UNIQUE,
    manifest_key          VARCHAR(512) NOT NULL,
    chain_scope           VARCHAR(16)  NOT NULL DEFAULT 'PER_TENANT'
                            CHECK (chain_scope IN ('PER_TENANT', 'GLOBAL')),
    min_seq               BIGINT       NOT NULL,
    max_seq               BIGINT       NOT NULL,
    row_count             BIGINT       NOT NULL,
    min_event_timestamp   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    max_event_timestamp   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    entry_hash_alg        VARCHAR(32)  NOT NULL,
    entry_hash_version    INTEGER      NOT NULL,
    -- Content digests (computed over the EXACT uploaded bytes) + S3 version ids.
    object_sha256         VARCHAR(64)  NOT NULL,
    object_version_id     VARCHAR(128) NOT NULL,
    manifest_sha256       VARCHAR(64)  NOT NULL,
    manifest_version_id   VARCHAR(128) NOT NULL,
    -- Retention pinned at first write (re-run compares against THIS, not now+7yr).
    retention_until       TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    worker_image_digest   VARCHAR(256),
    verify_status         VARCHAR(32)  NOT NULL DEFAULT 'VERIFIED',
    -- PER_TENANT per-object proof snapshot: [{tenant_id, first_prev_hash, last_entry_hash, row_count}, ...]
    tenant_anchors        JSONB        NOT NULL,
    created_at            TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_archive_ledger_max_seq ON audit_archive_ledger (max_seq);

COMMENT ON TABLE audit_archive_ledger IS
    'Faz 24 gitops#1250: immutable per-object archive evidence (ADR-0042 D4.3/D4.7) — content sha256 + S3 version ids + pinned retention + per-tenant chain anchors.';
COMMENT ON COLUMN audit_archive_ledger.object_version_id IS
    'S3 x-amz-version-id of the NDJSON.gz object (idempotency + tamper anchor; ADR-0042 D4.7).';
COMMENT ON COLUMN audit_archive_ledger.retention_until IS
    'COMPLIANCE retain-until pinned at first write; re-run retention check binds to this, not now+7yr.';

-- ---------------------------------------------------------------------------
-- Per-tenant chain watermark — the AUTHORITATIVE mutable anchor used to verify
-- PER_TENANT hash-chain continuity across batch boundaries (the first archived
-- row for a tenant in a batch must link to that tenant's last_entry_hash here).
-- Advanced TRANSACTIONALLY with the cursor + ledger (FOR UPDATE, after S3
-- version/checksum/retention verification). ADR-0042 D4.3/D4.4 (Codex C(c)).
-- ---------------------------------------------------------------------------
CREATE TABLE audit_archive_tenant_anchor (
    tenant_id          BIGINT       PRIMARY KEY,
    last_entry_hash    VARCHAR(64)  NOT NULL,
    last_archived_seq  BIGINT       NOT NULL,
    updated_at         TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now()
);

COMMENT ON TABLE audit_archive_tenant_anchor IS
    'Faz 24 gitops#1250: authoritative per-tenant PER_TENANT-chain watermark (last archived entry_hash + seq); O(1) continuity check across batches.';
