-- AG-038 live parity: DiagnosticsPayloadPolicy accepts lowercase v-prefixed
-- semver (for example v0.2.14). Keep the database backstop aligned so live
-- COLLECT_INVENTORY diagnostics result ingest does not fail after Java policy
-- acceptance.

ALTER TABLE endpoint_diagnostics_snapshots
    DROP CONSTRAINT IF EXISTS diag_snap_agent_version_re;

ALTER TABLE endpoint_diagnostics_snapshots
    ADD CONSTRAINT diag_snap_agent_version_re
    CHECK (
        agent_version = 'unknown'
        OR agent_version ~ '^v?[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?(\+[A-Za-z0-9.-]+)?$'
    ) NOT VALID;

ALTER TABLE endpoint_diagnostics_snapshots
    VALIDATE CONSTRAINT diag_snap_agent_version_re;
