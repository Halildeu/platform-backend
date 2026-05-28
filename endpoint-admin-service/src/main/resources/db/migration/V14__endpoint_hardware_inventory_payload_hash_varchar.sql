-- BE-022 V14 — payload_hash_sha256 CHAR(64) → VARCHAR(64) fix.
--
-- V13 created the column as CHAR(64) (PostgreSQL stores this as
-- `bpchar` — blank-padded character). The
-- EndpointHardwareInventorySnapshot entity has
-- `@Column(name="payload_hash_sha256", length=64, columnDefinition="char(64)")`
-- but Hibernate's schema-validate (Spring Boot ddl-auto=validate)
-- maps that to `VARCHAR`, not CHAR. Pod boot crashes with:
--
--   Schema-validation: wrong column type encountered in column
--   [payload_hash_sha256] in table
--   [endpoint_hardware_inventory_snapshots]; found [bpchar
--   (Types#CHAR)], but expecting [char(64) (Types#VARCHAR)]
--
-- The cleanest fix is to switch the DB column to VARCHAR(64) so it
-- matches Hibernate's expected mapping. The CHECK constraint on
-- the hash regex (`^[a-f0-9]{64}$`) is identical for both CHAR and
-- VARCHAR — only the storage form differs (CHAR is blank-padded,
-- VARCHAR isn't, but the regex anchors prevent any whitespace
-- variation).
--
-- This is a forward-compatible migration: existing rows (if any)
-- already store 64-char SHA-256 hex values; the CAST is a no-op
-- on the data, only the type metadata changes.

ALTER TABLE endpoint_hardware_inventory_snapshots
    ALTER COLUMN payload_hash_sha256 TYPE VARCHAR(64);
