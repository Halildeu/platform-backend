-- V57 — cross-device domain filter cache backfill (Faz 22.5, board #517).
--
-- One-time projection of the LATEST hardware-inventory domain onto the
-- endpoint_devices.domain_name indexed grid filter cache, for devices that
-- pre-date the EndpointHardwareInventoryService runtime reconcile (the agent
-- never carries the domain at enrollment, so the column was historically
-- null; the real domain only ever arrived via COLLECT_INVENTORY snapshots).
-- Runtime self-heal (reconcileDeviceDomainProjection on every ingest) keeps
-- it current thereafter.
--
-- Canonical truth = the latest endpoint_hardware_inventory_snapshots row per
-- (tenant_id, device_id), selected with the SAME tie-breaker the service uses
-- (collected_at DESC, created_at DESC, id DESC).
--
-- Fail-clear projection rule (mirrors
-- EndpointHardwareInventoryService.projectDomainFilterCache):
--   domain_joined IS TRUE AND domain_name non-blank -> lower(btrim(domain_name))
--   workgroup (FALSE) / unknown (NULL) / blank       -> NULL
--
-- Idempotent: IS DISTINCT FROM updates only rows whose cache is out of sync
-- (and null-safe — re-running this migration on an already-synced table is a
-- no-op). Org-scoped implicitly: each device is matched to a snapshot on its
-- own (device_id, tenant_id), so no cross-tenant projection is possible.
UPDATE endpoint_devices d
SET domain_name = sub.projected
FROM (
    SELECT DISTINCT ON (s.tenant_id, s.device_id)
           s.tenant_id,
           s.device_id,
           CASE
               WHEN s.domain_joined IS TRUE
                    AND s.domain_name IS NOT NULL
                    AND btrim(s.domain_name) <> ''
               THEN lower(btrim(s.domain_name))
               ELSE NULL
           END AS projected
    FROM endpoint_hardware_inventory_snapshots s
    ORDER BY s.tenant_id, s.device_id, s.collected_at DESC, s.created_at DESC, s.id DESC
) sub
WHERE d.id = sub.device_id
  AND d.tenant_id = sub.tenant_id
  AND d.domain_name IS DISTINCT FROM sub.projected;
