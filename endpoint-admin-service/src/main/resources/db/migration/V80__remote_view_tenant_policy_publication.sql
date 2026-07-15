-- Faz 23 #838 -- strict tenant VIEW_ONLY policy intake + immutable publication.
--
-- policy_change_approvals remains the generic four-eyes workflow. Only an
-- approval with a row in remote_view_policy_approval_intakes is publishable:
-- that companion row proves the source crossed the dedicated strict JSON
-- boundary before Map/jsonb materialisation. Publication accepts approval_id
-- only; it never accepts a replacement policy body or caller-authored digest.

CREATE TABLE remote_view_policy_approval_intakes (
    approval_id          UUID         PRIMARY KEY,
    tenant_id            UUID         NOT NULL,
    policy_id            VARCHAR(128) NOT NULL,
    policy_version       VARCHAR(32)  NOT NULL,
    canonical_source     TEXT         NOT NULL,
    policy_digest        VARCHAR(71)  NOT NULL,
    created_by_subject   VARCHAR(255) NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_rv_policy_intake_approval
        FOREIGN KEY (approval_id) REFERENCES policy_change_approvals(id),
    CONSTRAINT ck_rv_policy_intake_digest
        CHECK (policy_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_rv_policy_intake_source_nonblank
        CHECK (length(canonical_source) > 2),
    CONSTRAINT uq_rv_policy_intake_tenant_identity
        UNIQUE (tenant_id, policy_id, policy_version)
);

CREATE TABLE remote_view_policy_publications (
    id                       UUID         PRIMARY KEY,
    approval_id              UUID         NOT NULL,
    tenant_id                UUID         NOT NULL,
    policy_id                VARCHAR(128) NOT NULL,
    policy_version           VARCHAR(32)  NOT NULL,
    deployment_class         VARCHAR(32)  NOT NULL,
    canonical_source         TEXT         NOT NULL,
    policy_digest            VARCHAR(71)  NOT NULL,
    baseline_digest          VARCHAR(71)  NOT NULL,
    legal_evidence_digest    VARCHAR(71)  NOT NULL,
    legal_evidence_status    VARCHAR(32)  NOT NULL,
    supersedes_policy_digest VARCHAR(71),
    valid_from               TIMESTAMPTZ  NOT NULL,
    valid_until              TIMESTAMPTZ  NOT NULL,
    review_by                TIMESTAMPTZ  NOT NULL,
    legal_review_by          TIMESTAMPTZ  NOT NULL,
    published_by_subject     VARCHAR(255) NOT NULL,
    published_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_rv_policy_publication_intake
        FOREIGN KEY (approval_id) REFERENCES remote_view_policy_approval_intakes(approval_id),
    CONSTRAINT uq_rv_policy_publication_approval UNIQUE (tenant_id, approval_id),
    CONSTRAINT uq_rv_policy_publication_identity UNIQUE (tenant_id, policy_id, policy_version),
    CONSTRAINT uq_rv_policy_publication_digest UNIQUE (tenant_id, policy_digest),
    CONSTRAINT ck_rv_policy_publication_deployment
        CHECK (deployment_class IN ('bounded-test', 'production')),
    CONSTRAINT ck_rv_policy_publication_legal_status
        CHECK (legal_evidence_status IN ('tracked-pending', 'approved')),
    CONSTRAINT ck_rv_policy_publication_policy_digest
        CHECK (policy_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_rv_policy_publication_baseline_digest
        CHECK (baseline_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_rv_policy_publication_legal_digest
        CHECK (legal_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_rv_policy_publication_supersedes_digest
        CHECK (supersedes_policy_digest IS NULL OR supersedes_policy_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_rv_policy_publication_lifecycle
        CHECK (valid_from < review_by AND review_by <= valid_until
            AND valid_from < legal_review_by AND legal_review_by <= valid_until),
    CONSTRAINT ck_rv_policy_publication_source_nonblank
        CHECK (length(canonical_source) > 2)
);

CREATE INDEX idx_rv_policy_publication_active
    ON remote_view_policy_publications
    (tenant_id, valid_from DESC, published_at DESC);

-- One genesis and one child per content-addressed predecessor. These indexes
-- make two concurrent approval transactions unable to fork a tenant chain.
CREATE UNIQUE INDEX ux_rv_policy_publication_genesis
    ON remote_view_policy_publications (tenant_id)
    WHERE supersedes_policy_digest IS NULL;
CREATE UNIQUE INDEX ux_rv_policy_publication_successor
    ON remote_view_policy_publications (tenant_id, supersedes_policy_digest)
    WHERE supersedes_policy_digest IS NOT NULL;

CREATE TABLE remote_view_policy_revocations (
    id                    UUID         PRIMARY KEY,
    publication_id        UUID         NOT NULL,
    approval_id           UUID         NOT NULL,
    tenant_id             UUID         NOT NULL,
    policy_digest         VARCHAR(71)  NOT NULL,
    reason                VARCHAR(2048) NOT NULL,
    revoked_by_subject    VARCHAR(255) NOT NULL,
    revoked_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_rv_policy_revocation_publication
        FOREIGN KEY (publication_id) REFERENCES remote_view_policy_publications(id),
    CONSTRAINT fk_rv_policy_revocation_approval
        FOREIGN KEY (approval_id) REFERENCES policy_change_approvals(id),
    CONSTRAINT uq_rv_policy_revocation_publication UNIQUE (tenant_id, publication_id),
    CONSTRAINT uq_rv_policy_revocation_approval UNIQUE (tenant_id, approval_id),
    CONSTRAINT ck_rv_policy_revocation_digest
        CHECK (policy_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_rv_policy_revocation_reason CHECK (length(btrim(reason)) > 0)
);

CREATE OR REPLACE FUNCTION remote_view_policy_ledger_no_mutate()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (WORM): % is not permitted', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_rv_policy_intake_worm
    BEFORE UPDATE OR DELETE ON remote_view_policy_approval_intakes
    FOR EACH ROW EXECUTE FUNCTION remote_view_policy_ledger_no_mutate();
CREATE TRIGGER trg_rv_policy_intake_no_truncate
    BEFORE TRUNCATE ON remote_view_policy_approval_intakes
    FOR EACH STATEMENT EXECUTE FUNCTION remote_view_policy_ledger_no_mutate();

CREATE TRIGGER trg_rv_policy_publication_worm
    BEFORE UPDATE OR DELETE ON remote_view_policy_publications
    FOR EACH ROW EXECUTE FUNCTION remote_view_policy_ledger_no_mutate();
CREATE TRIGGER trg_rv_policy_publication_no_truncate
    BEFORE TRUNCATE ON remote_view_policy_publications
    FOR EACH STATEMENT EXECUTE FUNCTION remote_view_policy_ledger_no_mutate();

CREATE TRIGGER trg_rv_policy_revocation_worm
    BEFORE UPDATE OR DELETE ON remote_view_policy_revocations
    FOR EACH ROW EXECUTE FUNCTION remote_view_policy_ledger_no_mutate();
CREATE TRIGGER trg_rv_policy_revocation_no_truncate
    BEFORE TRUNCATE ON remote_view_policy_revocations
    FOR EACH STATEMENT EXECUTE FUNCTION remote_view_policy_ledger_no_mutate();
