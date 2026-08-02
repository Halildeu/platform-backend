-- ES-212 (#3370) — open CONFIDENTIAL and NAMED intake.
--
-- V21, not V20: Flyway reads two locations here (db/migration and db/vendor/{vendor}),
-- and V20 was already taken by db/vendor/postgresql/V20__enforce_ack_template_append_only.sql.
-- Checking only db/migration for the next free number finds a version that is not free.
--
-- The mode column has accepted all three values since V1; what was missing was
-- anywhere to put the reporter. Two tables close that gap: one says which modes an
-- organisation has turned on, the other holds the identity itself, encrypted.

-- ---------------------------------------------------------------------------
-- 1. Which modes is this organisation running?
-- ---------------------------------------------------------------------------
-- Tenant-parametric by owner decision (2026-08-02): every company decides its own
-- KVKK posture. One company wants named reports so managers can follow up directly;
-- the next has a works council that permits anonymous only. Hard-coding either one
-- would be wrong for the other, so the answer is data.
--
-- The absence of a row means ANONYMOUS only. That default is doing real work: it
-- reproduces today's behaviour exactly, so this migration cannot change what any
-- existing tenant collects. Turning identity collection on is an explicit act with
-- a name and a timestamp against it.
CREATE TABLE ethics_org_report_policy (
    org_id UUID PRIMARY KEY,
    anonymous_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    confidential_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    named_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    -- NULL = the identity follows the case's own retention. A tenant may set a
    -- shorter life for the identity than for the case: the narrative may need to be
    -- kept for the statutory period while the person behind it does not.
    identity_retention_days INTEGER,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by_subject VARCHAR(200) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_report_policy_retention CHECK (
        identity_retention_days IS NULL OR identity_retention_days > 0
    ),
    -- A policy that switches every mode off would silently close the reporting
    -- channel, which is the one failure this product exists to prevent. Anonymous
    -- intake is the floor; a tenant may add modes above it, never remove it.
    CONSTRAINT ck_report_policy_anonymous_floor CHECK (anonymous_enabled)
);

-- ---------------------------------------------------------------------------
-- 2. The reporter's identity — encrypted, and structurally unable to attach to
--    an anonymous report.
-- ---------------------------------------------------------------------------
-- ethics_reports.case_id is already UNIQUE, so this pair adds no new restriction;
-- it exists purely to give the identity table something to point at that carries
-- the mode with it.
ALTER TABLE ethics_reports ADD CONSTRAINT uq_ethics_reports_case_mode UNIQUE (case_id, mode);

CREATE TABLE reporter_identities (
    case_id UUID PRIMARY KEY REFERENCES ethics_cases(id),
    -- Carried here only so the composite foreign key below can bind it. Application
    -- code reads the mode from the report, never from this copy.
    mode VARCHAR(20) NOT NULL,

    -- Envelope encryption: the row holds ciphertext and the id of the key that
    -- produced it; the key itself lives in Vault and never in this database. A dump
    -- of this table — a backup tape, a replica, a careless pg_dump — yields nothing.
    key_id VARCHAR(120) NOT NULL,
    nonce BYTEA NOT NULL,
    ciphertext BYTEA NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    -- Deliberately absent: name, e-mail, phone, employee number, unit. There is no
    -- plaintext identity column at all, so no future query, index, log line, admin
    -- screen or ORM projection can accidentally select one. Anything that wants the
    -- identity must go through decryption, and decryption is where the authorisation
    -- check lives.
    CONSTRAINT ck_reporter_identity_mode CHECK (mode IN ('CONFIDENTIAL', 'NAMED')),
    -- The invariant that matters most, and the reason it is a foreign key rather
    -- than a comment: an identity row can only exist for a report whose OWN mode is
    -- the same value. Combined with the CHECK above, attaching an identity to an
    -- ANONYMOUS report is not "forbidden by policy" — it has no representation in
    -- the schema. A bug in the service layer cannot produce it, and neither can a
    -- direct INSERT by someone with database access.
    CONSTRAINT fk_reporter_identity_report_mode
        FOREIGN KEY (case_id, mode) REFERENCES ethics_reports (case_id, mode)
);

CREATE INDEX ix_reporter_identities_created ON reporter_identities (created_at);

COMMENT ON TABLE reporter_identities IS
    'ES-212. Encrypted reporter identity for CONFIDENTIAL and NAMED reports. Never '
    'populated for ANONYMOUS (enforced by fk_reporter_identity_report_mode). For '
    'CONFIDENTIAL the row is sealed: readable only after an EXECUTED reveal_request '
    'for the same case. For NAMED the reporter consented to handler visibility.';
