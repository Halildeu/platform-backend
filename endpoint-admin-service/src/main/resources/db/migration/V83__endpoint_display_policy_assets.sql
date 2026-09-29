-- V83 — #508 Endpoint Display Policy: managed wallpaper assets
--       (platform-backend#1203).
--
-- WHY: until now a wallpaper policy could only point at an image that already
-- existed on the endpoint's disk. The request carried metadata only and the
-- agent refused any path it could not os.Stat, so an admin had no way to put a
-- corporate image on a device through the product. This table is the store the
-- image travels through: an admin uploads it once, a policy references it by
-- content hash, and the device that policy targets downloads it over the
-- existing authenticated agent channel.
--
-- MODEL: content-addressed and immutable. One row per (org, sha256); the hash
-- is computed server-side at upload, so a policy that names a hash names exactly
-- one byte sequence. Rows are never updated — re-uploading the same image is a
-- no-op, a changed image is a new hash. Nothing is deleted here: a revision in
-- the append-only history may still reference an asset, and the hash is what
-- the audit trail points at.
--
-- SIZE: capped at 10 MiB (a 4K PNG fits comfortably). The cap is enforced in
-- the service with a clear 413 before anything is read into memory; the CHECK
-- below is the durable backstop, and the octet_length CHECK keeps the stored
-- size honest so a truncated write cannot masquerade as a complete image.
--
-- TYPES: png / jpeg / bmp — the three formats the Windows wallpaper policy
-- renders. The service verifies the magic bytes match the declared type, so a
-- renamed executable cannot be stored as "image/png".
--
-- ORG CONTRACT: mirrors V58 — tenant_id for reads, org_id for org-composite
-- keys, endpoint_org_id_compat_fill() fills org_id = tenant_id and the CHECKs
-- pin the match + NOT NULL.
--
-- sha256 is VARCHAR(64), not CHAR(64): the entity maps it as a plain String and
-- ddl-auto=validate rejects bpchar against varchar (the V14 / V59 fixes). The
-- regex CHECK is what pins it to exactly 64 lowercase hex characters.

CREATE TABLE endpoint_display_policy_assets (
    id                  UUID            NOT NULL,
    tenant_id           UUID            NOT NULL,
    org_id              UUID,
    sha256              VARCHAR(64)     NOT NULL,
    content_type        VARCHAR(64)     NOT NULL,
    size_bytes          INTEGER         NOT NULL,
    content             BYTEA           NOT NULL,
    created_by_subject  VARCHAR(255)    NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL,

    CONSTRAINT pk_endpoint_display_policy_assets PRIMARY KEY (id),
    CONSTRAINT endpoint_display_policy_assets_id_org_id_key UNIQUE (id, org_id),
    CONSTRAINT ck_edpa_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_edpa_content_type
        CHECK (content_type IN ('image/png', 'image/jpeg', 'image/bmp')),
    CONSTRAINT ck_edpa_size CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    CONSTRAINT ck_edpa_size_matches CHECK (octet_length(content) = size_bytes),
    CONSTRAINT endpoint_display_policy_assets_org_id_match
        CHECK (org_id IS NULL OR org_id = tenant_id),
    CONSTRAINT endpoint_display_policy_assets_org_id_not_null
        CHECK (org_id IS NOT NULL)
);

DROP TRIGGER IF EXISTS endpoint_display_policy_assets_org_id_compat ON endpoint_display_policy_assets;
CREATE TRIGGER endpoint_display_policy_assets_org_id_compat
    BEFORE INSERT OR UPDATE ON endpoint_display_policy_assets
    FOR EACH ROW EXECUTE FUNCTION endpoint_org_id_compat_fill();

-- Content addressing: one image per hash per org. The upload path relies on this
-- to make a repeated upload idempotent instead of storing a duplicate.
CREATE UNIQUE INDEX ux_edpa_org_sha256
    ON endpoint_display_policy_assets (org_id, sha256);

-- Immutable: an asset is identified by its hash, so its bytes can never change
-- under a policy that already names it.
CREATE OR REPLACE FUNCTION endpoint_display_policy_assets_immutable()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'endpoint_display_policy_assets is immutable (no % allowed)', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_edpa_immutable ON endpoint_display_policy_assets;
CREATE TRIGGER trg_edpa_immutable
    BEFORE UPDATE OR DELETE ON endpoint_display_policy_assets
    FOR EACH ROW EXECUTE FUNCTION endpoint_display_policy_assets_immutable();
