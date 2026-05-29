-- BE-024 — Software Inventory state history (Faz 22.5, software-inventory
-- diff/history). Codex thread 019e75a5 plan-time PARTIAL → ready_for_impl.
--
-- WHY THIS TABLE EXISTS
-- ---------------------
-- The BE-020I software-inventory read model (V8) is a SINGLE-ROW UPSERT per
-- device: endpoint_software_inventory_snapshots has UNIQUE(tenant_id,
-- device_id) and every full apps[] ingest physically DELETES the prior
-- endpoint_software_inventory_items rows (orphanRemoval=true +
-- replaceItems()). That model intentionally keeps only the latest item set,
-- so a "diff between the latest two snapshots" CANNOT be computed on-read —
-- the previous state is already gone.
--
-- BE-024 therefore adds an APPEND-ONLY software-state history (mirrors the
-- BE-022 hardware-inventory V13 + BE device-health V17 append-only
-- precedent EXACTLY) so the diff service can load the latest two retained
-- captures and compute added / removed / version-changed apps. The existing
-- single-row snapshot upsert behaviour is UNCHANGED — this is purely
-- additive; EndpointSoftwareInventoryService appends one history row inside
-- the SAME ingest transaction, only on a FULL apps[] payload (summary-only
-- and wingetEgress-only ingests do NOT append, since the app state did not
-- change).
--
-- STRICT v1 SCOPE
-- ---------------
-- This delivers added / removed / VERSION_CHANGED only. "Outdated /
-- availableVersion" deltas are OUT of scope (BE-024b — they depend on
-- AG-036 winget-upgrade data + the BE-023 catalog, not yet available).
-- There is no catalog join here and no availableVersion column.
--
-- REDACTION BOUNDARY
-- ------------------
-- The agent COLLECT_INVENTORY software payload is already fail-closed
-- sanitized by SoftwareInventoryPayloadPolicy BEFORE the parent
-- endpoint_command_results row is persisted (no licenseKey / productKey /
-- raw uninstallString / raw MSI ProductCode GUID / C:\Users\... paths /
-- SID / bearer / JWT / token / password). The apps_digest JSONB stored
-- here carries ONLY the diff-relevant whitelist per app
-- (appKey, displayName, publisher, version, msiProductCodeHash) — a strict
-- subset of the already-sanitized item fields. No user path, no install
-- log, no uninstall string ever reaches this column.
--
-- IDENTITY (appKey)
-- -----------------
-- endpoint_software_inventory_items has NO packageId column (packageId
-- lives only on the separate winget CATALOG table, which is NOT joined
-- here). The diff identity is therefore a SYNTHETIC stable key derived
-- from the installed-app natural key: a SHA-256 of
-- lower(displayName)|lower(publisher)|msiProductCodeHash. The DTO exposes
-- it as `appKey` (NOT `packageId`) so consumers do not mistake it for the
-- winget catalog package identifier (Codex 019e75a5 (c) absorb).
--
-- RETENTION
-- ---------
-- v1 retention is UNBOUNDED — every full apps[] re-collect appends a row.
-- A retention cap / pruning job is a deliberate follow-up (Codex 019e75a5
-- (d) absorb); for v1 the latest-per-device composite index keeps the
-- diff/history query cheap regardless of table size.
--
-- MIGRATION SEQUENCE GUARD: V17 (endpoint_device_health) was the last
-- applied migration on origin/main. V18 claims this slot exclusively for
-- BE-024. The V12 (id, tenant_id) UNIQUE on endpoint_devices is reused
-- here for the composite FK — it is NOT recreated.

CREATE TABLE endpoint_software_inventory_state_history (
    id                          UUID            NOT NULL,
    tenant_id                   UUID            NOT NULL,
    device_id                   UUID            NOT NULL,
    -- Pointer to the agent command-result that delivered this capture.
    -- NULL is allowed so result retention cleanup does not cascade-delete
    -- the software-state history; the partial UNIQUE INDEX below enforces
    -- 1-capture-per-command-result only when the column is set (agent
    -- SUBMIT path is idempotent — re-submitting the same result must NOT
    -- append a duplicate capture).
    source_command_result_id    UUID,
    -- Schema version of the originating software-inventory payload (mirrors
    -- the snapshot's schema_version). Positive integer; agent ships 1 today.
    schema_version              INTEGER         NOT NULL,
    -- Number of apps captured in this history row (== apps_digest length).
    app_count                   INTEGER         NOT NULL,
    -- Deterministic SHA-256 over the canonical apps_digest content. Two
    -- consecutive byte-identical captures share this hash, so the diff
    -- service can recognise a no-change re-collect (empty diff) and a
    -- future dedupe/pruning job can collapse duplicates.
    apps_digest_hash            VARCHAR(64)     NOT NULL,
    -- Whitelist-only per-app digest: a JSON array of objects
    --   { appKey, displayName, publisher, version, msiProductCodeHash }
    -- carrying ONLY the diff-relevant fields. Sanitized at ingest by
    -- SoftwareInventoryPayloadPolicy; no user paths / uninstall strings.
    apps_digest                 JSONB           NOT NULL,
    -- When the originating snapshot's apps were collected (mirrors
    -- snapshot.apps_collected_at); the diff "latest vs previous" ordering
    -- key.
    captured_at                 TIMESTAMPTZ     NOT NULL,
    created_at                  TIMESTAMPTZ     NOT NULL,

    CONSTRAINT pk_endpoint_software_inventory_state_history PRIMARY KEY (id),

    -- Schema version is a positive integer.
    CONSTRAINT ck_endpoint_software_inventory_state_history_schema_version_range
        CHECK (schema_version >= 1),

    -- App count is non-negative (an explicit empty inventory is app_count=0).
    CONSTRAINT ck_endpoint_software_inventory_state_history_app_count_range
        CHECK (app_count >= 0),

    -- Hash is exactly 64 lowercase hex chars (HexFormat lowercase output).
    CONSTRAINT ck_endpoint_software_inventory_state_history_hash_format
        CHECK (apps_digest_hash ~ '^[a-f0-9]{64}$'),

    -- apps_digest is always a JSON array (never an object / scalar).
    CONSTRAINT ck_endpoint_software_inventory_state_history_digest_shape
        CHECK (jsonb_typeof(apps_digest) = 'array'),

    -- Composite FK to endpoint_devices — tenant column physically enforced
    -- (parity with V13 hardware-inventory / V12 install-audit). ON DELETE
    -- CASCADE: removing a device removes its software-state history.
    CONSTRAINT fk_endpoint_software_inventory_state_history_device
        FOREIGN KEY (device_id, tenant_id)
        REFERENCES endpoint_devices (id, tenant_id) ON DELETE CASCADE,

    -- Pointer to the originating agent command-result. ON DELETE SET NULL
    -- preserves the software-state history even when command-result
    -- retention cleans up the result row (parity with V13).
    CONSTRAINT fk_endpoint_software_inventory_state_history_command_result
        FOREIGN KEY (source_command_result_id)
        REFERENCES endpoint_command_results (id) ON DELETE SET NULL
);

-- Partial UNIQUE on source_command_result_id (NULL allowed; agent
-- SUBMIT path enforces 1-capture-per-command-result, manual/test ingest
-- paths leave it NULL). Mirrors V13.
CREATE UNIQUE INDEX uq_endpoint_software_inventory_state_history_source_cmd_result
    ON endpoint_software_inventory_state_history (source_command_result_id)
    WHERE source_command_result_id IS NOT NULL;

-- Latest-per-device + history query path. Column order matches the
-- deterministic tiebreaker (captured_at DESC, created_at DESC, id DESC)
-- so PG can serve the diff "latest two" + the paged history view from an
-- index scan without a sort.
CREATE INDEX idx_endpoint_software_inventory_state_history_tenant_device_time
    ON endpoint_software_inventory_state_history
        (tenant_id, device_id, captured_at, created_at, id);
