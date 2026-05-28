-- BE-022 — Endpoint Hardware Inventory (Faz 22.5). Codex thread
-- 019e7007 iter-4 AGREE (ready_for_impl=true). Builds on V2 (commands /
-- command_results), V8 (software inventory), and V12 (install audit +
-- composite-FK enablers on endpoint_devices / endpoint_commands)
-- without breaking any existing contract.
--
-- This migration delivers three DDL slices:
--
--   1. endpoint_hardware_inventory_snapshots — append-only history of
--      hardware/system observations harvested via the existing
--      `COLLECT_INVENTORY` agent contract. Each row captures the CPU,
--      RAM, OS, BIOS, system manufacturer/model, domain join state,
--      and last-boot timestamp at one point in time, plus an opaque
--      `redacted_payload` JSONB carrying any additional sanitized
--      hardware facts the agent surfaced. Sensitive fields (BIOS /
--      disk serials, user paths, Windows SIDs, machine GUIDs, tokens)
--      are stripped or fail-closed rejected pre-persist by
--      HardwareInventoryPayloadPolicy — they never enter this table
--      or endpoint_command_results.result_payload.
--
--   2. endpoint_hardware_inventory_disks — per-disk facets for the
--      latest snapshot. Composite (snapshot_id, tenant_id) FK enforces
--      tenant integrity at the DB layer (parity with V12 install
--      audit). ON DELETE CASCADE on snapshots means hardware history
--      cleanup is a single DELETE on the snapshot row.
--
--   3. endpoint_hardware_inventory_network_interfaces — per-NIC
--      facets. IP addresses are stored as a JSONB array (Codex iter-4
--      AGREE: repo-native vs PostgreSQL TEXT[]; future IP-filter
--      backlog can normalize to a child table without changing the
--      snapshot shape).
--
-- Append-only history is intentional. The latest snapshot is a query
-- (ORDER BY collected_at DESC, created_at DESC, id DESC), NOT an
-- "upsert into current row" pattern — BE-020I's UNIQUE(tenant_id,
-- device_id) software-inventory model would erase history needed by
-- WEB-013 hardware view's history accordion (Codex iter-2 P1 absorb).
--
-- Migration sequence guard: V12 (BE-021 install audit) was the last
-- applied migration on origin/main. V13 claims this slot exclusively
-- for BE-022. The V12 (id, tenant_id) UNIQUEs on endpoint_devices and
-- endpoint_commands are reused here for composite FKs — they are NOT
-- recreated.

-- ---------------------------------------------------------------------
-- 1. endpoint_hardware_inventory_snapshots — append-only history
-- ---------------------------------------------------------------------
CREATE TABLE endpoint_hardware_inventory_snapshots (
    id                              UUID            NOT NULL,
    tenant_id                       UUID            NOT NULL,
    device_id                       UUID            NOT NULL,
    -- Pointer to the agent command-result that delivered this snapshot.
    -- NULL is allowed so result cleanup (retention) does not cascade
    -- delete the hardware history; UNIQUE because the agent SUBMIT
    -- result path must be idempotent — re-submitting the same command
    -- result must NOT create a second snapshot (HardwareInventoryService
    -- catches DataIntegrityViolationException and returns the existing
    -- row).
    source_command_result_id        UUID,
    schema_version                  INTEGER         NOT NULL,
    supported                       BOOLEAN         NOT NULL,

    -- Stable scalar columns. Optional facts persist as NULL when the
    -- agent could not collect them on this OS / platform / privilege
    -- level — query consumers must tolerate nulls per Codex 019e6fd1
    -- nullability discipline.
    cpu_model                       TEXT,
    cpu_cores                       SMALLINT,
    cpu_frequency_mhz               INTEGER,
    ram_total_bytes                 BIGINT,
    ram_available_bytes             BIGINT,
    os_name                         TEXT,
    os_version                      TEXT,
    os_kernel                       TEXT,
    os_arch                         TEXT,
    bios_vendor                     TEXT,
    bios_version                    TEXT,
    manufacturer                    TEXT,
    system_model                    TEXT,
    domain_joined                   BOOLEAN,
    domain_name                     TEXT,
    last_boot_at                    TIMESTAMPTZ,

    -- Integrity + provenance.
    payload_hash_sha256             CHAR(64)        NOT NULL,
    redacted_payload                JSONB           NOT NULL DEFAULT '{}'::jsonb,
    probe_errors                    JSONB           NOT NULL DEFAULT '[]'::jsonb,

    collected_at                    TIMESTAMPTZ     NOT NULL,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version                         BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_endpoint_hardware_inventory_snapshots PRIMARY KEY (id),

    -- (id, tenant_id) UNIQUE so child tables (disks, interfaces) can
    -- bind via composite FK and the tenant column is physically
    -- enforced (parity with V10 catalog + V12 commands / devices).
    CONSTRAINT uq_endpoint_hardware_inventory_snapshots_id_tenant
        UNIQUE (id, tenant_id),

    -- Idempotency for the agent SUBMIT-result hook. NULL is allowed
    -- for manual / test snapshots that do not originate from a
    -- command-result; the partial UNIQUE INDEX below enforces the
    -- constraint only when source_command_result_id is set.
    CONSTRAINT ck_endpoint_hardware_inventory_snapshots_hash_format
        CHECK (payload_hash_sha256 ~ '^[a-f0-9]{64}$'),

    CONSTRAINT ck_endpoint_hardware_inventory_snapshots_payload_shape
        CHECK (jsonb_typeof(redacted_payload) = 'object'),

    CONSTRAINT ck_endpoint_hardware_inventory_snapshots_probe_errors_shape
        CHECK (jsonb_typeof(probe_errors) = 'array'),

    -- Schema version is a positive integer (agent SUBMIT-result sends
    -- 1 today; future agents may bump as the contract evolves).
    CONSTRAINT ck_endpoint_hardware_inventory_snapshots_schema_version_range
        CHECK (schema_version >= 1),

    -- Non-negative scalars where the agent has actually populated a
    -- value. Hardware-reasonable upper bounds are intentionally
    -- omitted — service-layer validation handles range plausibility.
    CONSTRAINT ck_endpoint_hardware_inventory_snapshots_cpu_cores_range
        CHECK (cpu_cores IS NULL OR cpu_cores >= 0),

    CONSTRAINT ck_endpoint_hardware_inventory_snapshots_cpu_frequency_range
        CHECK (cpu_frequency_mhz IS NULL OR cpu_frequency_mhz >= 0),

    CONSTRAINT ck_endpoint_hardware_inventory_snapshots_ram_total_range
        CHECK (ram_total_bytes IS NULL OR ram_total_bytes >= 0),

    CONSTRAINT ck_endpoint_hardware_inventory_snapshots_ram_available_range
        CHECK (ram_available_bytes IS NULL OR ram_available_bytes >= 0),

    -- Composite FK to endpoint_devices — tenant column is enforced at
    -- the DB layer (Codex iter-4 absorb). ON DELETE CASCADE matches the
    -- V12 install-audit / V8 software-inventory patterns: when a
    -- device is removed, its hardware history is removed with it.
    CONSTRAINT fk_endpoint_hardware_inventory_snapshots_device
        FOREIGN KEY (device_id, tenant_id)
        REFERENCES endpoint_devices (id, tenant_id) ON DELETE CASCADE,

    -- Pointer to the originating agent command-result. ON DELETE SET
    -- NULL preserves the hardware history even if command-result
    -- retention cleans up the command-result row (Codex iter-4
    -- absorb).
    CONSTRAINT fk_endpoint_hardware_inventory_snapshots_command_result
        FOREIGN KEY (source_command_result_id)
        REFERENCES endpoint_command_results (id) ON DELETE SET NULL
);

-- Partial UNIQUE on source_command_result_id (NULL allowed; agent
-- SUBMIT path enforces 1-snapshot-per-command-result, manual/test
-- ingest paths leave it NULL).
CREATE UNIQUE INDEX uq_endpoint_hardware_inventory_snapshots_source_cmd_result
    ON endpoint_hardware_inventory_snapshots (source_command_result_id)
    WHERE source_command_result_id IS NOT NULL;

-- Latest snapshot per device — primary query path for WEB-013
-- hardware view + future BE-024 hardware compliance evaluator.
-- DESC order on collected_at + created_at + id provides a stable
-- tiebreaker without sorting in PG when the index satisfies the
-- ORDER BY clause.
CREATE INDEX idx_endpoint_hardware_inventory_snapshots_tenant_device_time
    ON endpoint_hardware_inventory_snapshots
       (tenant_id, device_id, collected_at DESC, created_at DESC, id DESC);

-- ---------------------------------------------------------------------
-- 2. endpoint_hardware_inventory_disks — per-disk facets per snapshot
-- ---------------------------------------------------------------------
CREATE TABLE endpoint_hardware_inventory_disks (
    id                              UUID            NOT NULL,
    snapshot_id                     UUID            NOT NULL,
    tenant_id                       UUID            NOT NULL,
    device_path                     TEXT,
    model                           TEXT,
    media_type                      VARCHAR(16),
    capacity_bytes                  BIGINT,
    free_bytes                      BIGINT,
    bus_type                        VARCHAR(16),
    is_removable                    BOOLEAN,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_endpoint_hardware_inventory_disks PRIMARY KEY (id),

    CONSTRAINT ck_endpoint_hardware_inventory_disks_capacity_range
        CHECK (capacity_bytes IS NULL OR capacity_bytes >= 0),

    CONSTRAINT ck_endpoint_hardware_inventory_disks_free_range
        CHECK (free_bytes IS NULL OR free_bytes >= 0),

    CONSTRAINT ck_endpoint_hardware_inventory_disks_media_type
        CHECK (media_type IS NULL OR media_type IN ('SSD', 'HDD', 'NVME', 'UNKNOWN')),

    -- Composite FK to the parent snapshot. (snapshot_id, tenant_id)
    -- pair forces the disk row to share the tenant of its snapshot —
    -- a service bug cannot persist a disk under the wrong tenant.
    CONSTRAINT fk_endpoint_hardware_inventory_disks_snapshot
        FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES endpoint_hardware_inventory_snapshots (id, tenant_id)
        ON DELETE CASCADE
);

-- Per-snapshot lookup (latest-snapshot rendering joins disks by
-- snapshot_id; tenant_id is also indexed for tenant-scoped pruning).
CREATE INDEX idx_endpoint_hardware_inventory_disks_snapshot
    ON endpoint_hardware_inventory_disks (snapshot_id, tenant_id);

-- ---------------------------------------------------------------------
-- 3. endpoint_hardware_inventory_network_interfaces — per-NIC facets
-- ---------------------------------------------------------------------
CREATE TABLE endpoint_hardware_inventory_network_interfaces (
    id                              UUID            NOT NULL,
    snapshot_id                     UUID            NOT NULL,
    tenant_id                       UUID            NOT NULL,
    name                            TEXT,
    -- MAC stored lowercase (`aa:bb:cc:dd:ee:ff`) — service-layer
    -- normalizes before insert.
    mac_address                     VARCHAR(17),
    interface_type                  VARCHAR(16),
    link_state                      VARCHAR(16),
    -- JSONB array of IP literals (IPv4 / IPv6 / link-local / scoped).
    -- Repo-native vs PostgreSQL TEXT[] (Codex iter-4 AGREE).
    ip_addresses                    JSONB           NOT NULL DEFAULT '[]'::jsonb,
    created_at                      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_endpoint_hardware_inventory_network_interfaces PRIMARY KEY (id),

    CONSTRAINT ck_endpoint_hardware_inventory_network_interfaces_mac_format
        CHECK (mac_address IS NULL OR mac_address ~ '^[0-9a-f]{2}(:[0-9a-f]{2}){5}$'),

    CONSTRAINT ck_endpoint_hardware_inventory_network_interfaces_type
        CHECK (interface_type IS NULL OR interface_type IN ('ETHERNET', 'WIFI', 'LOOPBACK', 'VIRTUAL', 'UNKNOWN')),

    CONSTRAINT ck_endpoint_hardware_inventory_network_interfaces_link_state
        CHECK (link_state IS NULL OR link_state IN ('UP', 'DOWN', 'UNKNOWN')),

    CONSTRAINT ck_endpoint_hardware_inventory_network_interfaces_ip_shape
        CHECK (jsonb_typeof(ip_addresses) = 'array'),

    -- Composite FK to the parent snapshot.
    CONSTRAINT fk_endpoint_hardware_inventory_network_interfaces_snapshot
        FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES endpoint_hardware_inventory_snapshots (id, tenant_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_endpoint_hardware_inventory_network_interfaces_snapshot
    ON endpoint_hardware_inventory_network_interfaces (snapshot_id, tenant_id);
