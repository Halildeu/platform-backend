-- Faz 35 ES-203 / B+ slice 1 — name a person on a case as a principal.
--
-- Until now a case carried `assigned_to`, a free-text label: `VARCHAR(200)`
-- with no format and no reference to any principal. Live values included
-- `team:ethics-test` and `jbjb` — the second is junk somebody typed and the
-- system accepted. Authorization, meanwhile, is keyed by Keycloak subject:
--
--   check(user:<kc_subject>, case_viewer, ethics_product:<org_id>)
--
-- so nothing on the case could be handed to the authorization plane. That gap
-- is what blocks third-party conflict declaration, pre-disclosure routing and
-- reveal-approver exclusion: all three need to name someone, and there was no
-- name to give.
--
-- This table is the authoritative answer. `assigned_to` is retired separately
-- (slice 2) rather than here, so the authorization fix does not wait on a UI
-- change — but the two must not coexist as rival sources for long, because an
-- operator reading a stale label while authority sits elsewhere is a security
-- problem, not a display bug.
--
-- What is deliberately absent: names, emails, and any decoded identity. The
-- subject is the same opaque UUID the policy engine already uses; a display
-- name is derived from the identity source at read time, never stored here as
-- an independently editable field.

CREATE TABLE ethics_case_participants (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES ethics_cases(id),
    org_id UUID NOT NULL,
    -- Keycloak subject. The same identity the OpenFGA tuple names, so the two
    -- stores cannot disagree about *who* while agreeing about *what*.
    kc_subject VARCHAR(64) NOT NULL,
    -- Constrained to the roles the authorization model defines. A free-text
    -- role would reintroduce exactly the problem this table exists to remove.
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- sha256 of the acting subject, matching the discipline of every other
    -- audit surface here: who acted is answerable, but not readable.
    created_by_hash VARCHAR(64) NOT NULL,
    CONSTRAINT ethics_case_participants_role_allowed
        CHECK (role IN ('triager', 'handler', 'evidence_approver')),
    CONSTRAINT ethics_case_participants_unique
        UNIQUE (case_id, kc_subject, role)
);

-- The read on every case detail: who is on this case.
CREATE INDEX ethics_case_participants_case_idx
    ON ethics_case_participants (case_id);

-- The read behind "which cases is this person on", used by routing and by the
-- conflict paths. Tenant-scoped so a query cannot accidentally cross orgs.
CREATE INDEX ethics_case_participants_subject_idx
    ON ethics_case_participants (org_id, kc_subject);
