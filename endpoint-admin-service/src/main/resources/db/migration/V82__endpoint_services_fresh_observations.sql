-- Faz 22.6 #2913 — preserve fresh critical-service observations.
--
-- V24 made (tenant_id, device_id, payload_hash_sha256) unique. That collapsed
-- a later successful COLLECT_INVENTORY result when service state was
-- unchanged, so the product kept showing the first observation timestamp.
-- Idempotency belongs to source_command_result_id: replaying the same result
-- remains a no-op, while a new command result must append a new observation.

DROP INDEX IF EXISTS svcs_snap_tenant_device_hash_uq;

CREATE INDEX IF NOT EXISTS svcs_snap_tenant_device_hash_ix
    ON endpoint_services_snapshots
       (tenant_id, device_id, payload_hash_sha256);
