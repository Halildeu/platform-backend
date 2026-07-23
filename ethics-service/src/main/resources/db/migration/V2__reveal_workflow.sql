-- Faz 35 ES-303 — Reveal request workflow with 4-eyes + WORM audit log.
-- Only the KVKK Md.28 exemption (adli/idari mercii yazılı talebi) or a
-- GDPR Art.6(1)(c/e) legal obligation can trigger a reveal. Two distinct
-- reviewers must approve before execution — enforced both at the app and
-- at the schema (see ck_reveal_two_person_rule).

CREATE TABLE reveal_requests (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES ethics_cases(id),
    requester_subject VARCHAR(200) NOT NULL,
    requester_name VARCHAR(200) NOT NULL,
    legal_basis VARCHAR(40) NOT NULL,
    legal_authority VARCHAR(400) NOT NULL,
    reference_number VARCHAR(200) NOT NULL,
    justification VARCHAR(4000) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    first_approver_subject VARCHAR(200),
    first_approver_name VARCHAR(200),
    first_approver_role VARCHAR(80),
    first_approved_at TIMESTAMP WITH TIME ZONE,
    second_approver_subject VARCHAR(200),
    second_approver_name VARCHAR(200),
    second_approver_role VARCHAR(80),
    second_approved_at TIMESTAMP WITH TIME ZONE,
    rejected_at TIMESTAMP WITH TIME ZONE,
    rejected_by_subject VARCHAR(200),
    rejection_reason VARCHAR(4000),
    executed_at TIMESTAMP WITH TIME ZONE,
    executed_by_subject VARCHAR(200),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_reveal_status CHECK (status IN ('PENDING','ONE_APPROVED','READY','EXECUTED','REJECTED')),
    CONSTRAINT ck_reveal_legal_basis CHECK (legal_basis IN ('KVKK_MD28','GDPR_ART6_1C','GDPR_ART6_1E','COURT_ORDER')),
    CONSTRAINT ck_reveal_two_person_rule CHECK (
        first_approver_subject IS NULL
        OR second_approver_subject IS NULL
        OR first_approver_subject <> second_approver_subject
    )
);
CREATE INDEX ix_reveal_requests_case ON reveal_requests(case_id);
CREATE INDEX ix_reveal_requests_status ON reveal_requests(status, requested_at);

-- WORM audit log: application never issues UPDATE or DELETE against this table.
-- Repository exposes only save() (append) and findAll* (read); no delete/save-existing
-- method is defined. Downstream ES-311 owner-approval package documents rotation
-- to an off-cluster archive for the retention window.
CREATE TABLE reveal_audit_log (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES reveal_requests(id),
    case_id UUID NOT NULL REFERENCES ethics_cases(id),
    event_type VARCHAR(60) NOT NULL,
    actor_subject VARCHAR(200) NOT NULL,
    actor_role VARCHAR(80) NOT NULL,
    payload VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_reveal_audit_event CHECK (event_type IN (
        'REQUEST_SUBMITTED','APPROVAL_RECORDED','REQUEST_REJECTED','REQUEST_EXECUTED','ACCESS_RETRIEVED'
    ))
);
CREATE INDEX ix_reveal_audit_request ON reveal_audit_log(request_id, created_at);
CREATE INDEX ix_reveal_audit_case ON reveal_audit_log(case_id, created_at);
