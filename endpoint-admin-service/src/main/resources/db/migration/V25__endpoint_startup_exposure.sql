-- BE — Endpoint Startup Apps & Exposure Summary (Faz 22.5, AG-040-be ingest).
-- Mirrors V24 AG-039-be services 3-table composite-FK pattern; extends snapshot
-- root with two additional exposure scalars (rdp_enabled +
-- windows_firewall_event_log_enabled) flat on the row (Codex 019e8387 plan
-- iter-1 absorb: eventLog as scalar flat, not separate child table).
--
-- Wire contract: platform-agent docs/COMMAND-CONTRACT.md §19 (AG-040 PR #48
-- pending merge). The AG-040 startup/exposure block is carried under the
-- COLLECT_INVENTORY result at details.inventory.startupExposure
-- (schemaVersion=1).
-- Source of truth = platform-agent internal/inventory/startup_exposure.go
-- StartupExposureResult.
--
-- v1 bounded Location enum (10 anchors, Codex 019e8387 plan iter-1 P1 #1):
--   Registry: HKLM_RUN, HKLM_RUNONCE, HKLM_WOW6432_RUN, HKCU_RUN, HKCU_RUNONCE
--   Filesystem: STARTUP_FOLDER_COMMON, STARTUP_FOLDER_USER
--   Scheduled Tasks (BUCKET only): TASK_SCHEDULER:ROOT,
--                                  TASK_SCHEDULER:MICROSOFT_WINDOWS,
--                                  TASK_SCHEDULER:CUSTOM
--
-- REDACTION BOUNDARY (security invariant — DO NOT widen):
--   the per-entry wire shape is EXACTLY {name, location, enabled,
--   probeOrigin}; the Location column carries the AUTORUN ANCHOR enum,
--   NEVER a full executable path / command line / RunAs / working dir.
--   StartupExposureProbeError carries Code + optional Source
--   (allowlist-only Location enum) + optional Summary (bounded ≤200
--   chars, CRLF + control-char REJECT, value-level URL/Bearer/IP/token
--   denylist policy-side via SUMMARY_VALUE_DENYLIST_RE reuse).
--   RDP active session count is NOT persisted (usage telemetry leak).
--   Per-rule firewall enum is NOT persisted (only the scalar event-log
--   enabled boolean).
--
-- FAIL-CLOSED EVIDENCE: supported=false (non-Windows runtime) +
-- probe_complete=false (any probe error) are persisted AS evidence.
-- Consumers MUST NOT render an incomplete probe as "no autorun entries".
--
-- CANONICAL-FORM PAYLOAD HASH SCOPE (matches policy projection):
--   INCLUDED: schemaVersion, supported, probeComplete, rdpEnabled,
--             windowsFirewallEventLogEnabled, startupApps (full ordered
--             list with all 4 fields per entry), probeErrors (ordered
--             list with code + source + summary), probeDurationMs.
--   EXCLUDED: none. Each fresh observation appends a new snapshot and
--             /latest reflects the most recent measured state.
--
-- COLLECTED_AT IS SERVER-CONTROLLED: from EndpointCommandResult.reportedAt
-- at ingest. Payload-level collectedAt would be REJECTED by strict-allowlist.

-- ---------------------------------------------------------------------------
-- SNAPSHOT ROOT TABLE
-- ---------------------------------------------------------------------------

CREATE TABLE endpoint_startup_exposure_snapshots (
    id                                   UUID                     NOT NULL,
    tenant_id                            UUID                     NOT NULL,
    device_id                            UUID                     NOT NULL,
    source_command_result_id             UUID                     NULL,
    schema_version                       INTEGER                  NOT NULL,
    supported                            BOOLEAN                  NOT NULL,
    probe_complete                       BOOLEAN                  NOT NULL,
    rdp_enabled                          BOOLEAN                  NOT NULL,
    windows_firewall_event_log_enabled   BOOLEAN                  NOT NULL,
    probe_duration_ms                    INTEGER                  NOT NULL,
    payload_hash_sha256                  VARCHAR(64)              NOT NULL,
    collected_at                         TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at                           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    CONSTRAINT se_snap_pk PRIMARY KEY (id),
    CONSTRAINT se_snap_id_tenant_uq UNIQUE (id, tenant_id),

    CONSTRAINT se_snap_schema_version_ck CHECK (schema_version = 1),
    CONSTRAINT se_snap_probe_duration_ck
        CHECK (probe_duration_ms >= 0 AND probe_duration_ms <= 120000),
    CONSTRAINT se_snap_payload_hash_re
        CHECK (payload_hash_sha256 ~ '^[0-9a-f]{64}$'),

    CONSTRAINT se_snap_device_fk
        FOREIGN KEY (device_id, tenant_id)
        REFERENCES endpoint_devices (id, tenant_id)
        ON DELETE CASCADE,

    CONSTRAINT se_snap_source_cmd_fk
        FOREIGN KEY (source_command_result_id)
        REFERENCES endpoint_command_results (id)
        ON DELETE SET NULL
);

CREATE UNIQUE INDEX se_snap_source_cmd_partial_uq
    ON endpoint_startup_exposure_snapshots (source_command_result_id)
    WHERE source_command_result_id IS NOT NULL;

CREATE UNIQUE INDEX se_snap_tenant_device_hash_uq
    ON endpoint_startup_exposure_snapshots (tenant_id, device_id, payload_hash_sha256);

CREATE INDEX se_snap_tenant_device_collected_at_ix
    ON endpoint_startup_exposure_snapshots
       (tenant_id, device_id, collected_at DESC, created_at DESC, id DESC);

-- ---------------------------------------------------------------------------
-- CHILD: STARTUP APP ENTRIES
-- ---------------------------------------------------------------------------

CREATE TABLE endpoint_startup_exposure_apps (
    id              UUID         NOT NULL,
    snapshot_id     UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    row_ordinal     INTEGER      NOT NULL,
    name            VARCHAR(256) NOT NULL,
    location        VARCHAR(48)  NOT NULL,
    enabled         BOOLEAN      NOT NULL,
    probe_origin    VARCHAR(16)  NOT NULL,

    CONSTRAINT se_app_pk PRIMARY KEY (id),
    CONSTRAINT se_app_snap_ord_uq UNIQUE (snapshot_id, row_ordinal),

    CONSTRAINT se_app_snapshot_fk FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES endpoint_startup_exposure_snapshots (id, tenant_id)
        ON DELETE CASCADE,

    CONSTRAINT se_app_row_ordinal_ck CHECK (row_ordinal >= 0),

    -- Bounded Location enum (10 anchors; Codex 019e8387 plan iter-1 P1 #1).
    CONSTRAINT se_app_location_ck
        CHECK (location IN (
            'HKLM_RUN', 'HKLM_RUNONCE', 'HKLM_WOW6432_RUN',
            'HKCU_RUN', 'HKCU_RUNONCE',
            'STARTUP_FOLDER_COMMON', 'STARTUP_FOLDER_USER',
            'TASK_SCHEDULER:ROOT', 'TASK_SCHEDULER:MICROSOFT_WINDOWS',
            'TASK_SCHEDULER:CUSTOM'
        )),

    -- Bounded ProbeOrigin enum.
    CONSTRAINT se_app_probe_origin_ck
        CHECK (probe_origin IN ('REGISTRY', 'SCHEDULED_TASK')),

    -- Name bounded length + control-char reject (DB secondary guard).
    -- Codex 019e83a8 iter-1 P2#8 absorb: use POSIX named class
    -- `[[:cntrl:]]` which is the canonical PG-portable form for
    -- C0 + DEL control characters (covers 0x00-0x1F + 0x7F). The
    -- previous `[\r\n\t\x00-\x1F\x7F]` form relied on escape semantics
    -- that vary by PG client and was not unit-tested.
    CONSTRAINT se_app_name_len_ck
        CHECK (char_length(name) BETWEEN 1 AND 256),
    CONSTRAINT se_app_name_no_ctrl_ck
        CHECK (name !~ '[[:cntrl:]]')
);

-- Index for "which devices have entry X in autorun anchor Y" fleet queries.
CREATE INDEX se_app_tenant_location_ix
    ON endpoint_startup_exposure_apps (tenant_id, location);

CREATE INDEX se_app_tenant_snapshot_ix
    ON endpoint_startup_exposure_apps (tenant_id, snapshot_id, row_ordinal);

-- ---------------------------------------------------------------------------
-- CHILD: PROBE ERRORS
-- ---------------------------------------------------------------------------

CREATE TABLE endpoint_startup_exposure_probe_errors (
    id              UUID         NOT NULL,
    snapshot_id     UUID         NOT NULL,
    tenant_id       UUID         NOT NULL,
    row_ordinal     INTEGER      NOT NULL,
    code            VARCHAR(64)  NOT NULL,
    source          VARCHAR(48)  NULL,
    summary         VARCHAR(200) NULL,

    CONSTRAINT se_pe_pk PRIMARY KEY (id),
    CONSTRAINT se_pe_snap_ord_uq UNIQUE (snapshot_id, row_ordinal),

    CONSTRAINT se_pe_snapshot_fk FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES endpoint_startup_exposure_snapshots (id, tenant_id)
        ON DELETE CASCADE,

    CONSTRAINT se_pe_row_ordinal_ck CHECK (row_ordinal >= 0),

    -- ProbeError code bounded enum (10 codes; NAME_VALUE_REDACTED
    -- added in Codex 019e83a8 iter-1 P1#2 absorb — agent emits this
    -- code when omitting a startup entry whose name carries a
    -- path/command fragment).
    CONSTRAINT se_pe_code_ck
        CHECK (code IN ('UNSUPPORTED_PLATFORM', 'REGISTRY_QUERY_FAILED',
                        'TASK_SCHEDULER_UNAVAILABLE',
                        'TASK_SCHEDULER_QUERY_FAILED',
                        'STARTUP_FOLDER_UNREADABLE',
                        'RDP_PROBE_FAILED', 'FIREWALL_PROBE_FAILED',
                        'ENTRY_CAP_APPLIED', 'NO_EVIDENCE',
                        'NAME_VALUE_REDACTED')),

    -- source allowlist-only when present (autorun anchor enum reuse).
    CONSTRAINT se_pe_source_allowlist_ck
        CHECK (
            source IS NULL
            OR source IN (
                'HKLM_RUN', 'HKLM_RUNONCE', 'HKLM_WOW6432_RUN',
                'HKCU_RUN', 'HKCU_RUNONCE',
                'STARTUP_FOLDER_COMMON', 'STARTUP_FOLDER_USER',
                'TASK_SCHEDULER:ROOT', 'TASK_SCHEDULER:MICROSOFT_WINDOWS',
                'TASK_SCHEDULER:CUSTOM'
            )
        ),

    -- Summary bounded length + CR/LF reject (DB secondary guard; policy
    -- primary handles value-level denylist + tab/control-char).
    CONSTRAINT se_pe_summary_len_ck
        CHECK (
            summary IS NULL
            OR (char_length(summary) BETWEEN 1 AND 200)
        ),
    CONSTRAINT se_pe_summary_no_crlf_ck
        CHECK (
            summary IS NULL
            OR summary !~ '[\r\n]'
        )
);

CREATE INDEX se_pe_tenant_snapshot_ix
    ON endpoint_startup_exposure_probe_errors (tenant_id, snapshot_id, row_ordinal);
