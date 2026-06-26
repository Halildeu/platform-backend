-- Faz 22.6 #548 — endpoint_enrollments status CHECK must include the TPM
-- lifecycle statuses the /attest flow writes.
--
-- The EnrollmentStatus enum (Codex 019ec723 REVISE#2) added TPM_IN_PROGRESS
-- (set at /attest BEFORE the Vault call, so a concurrent /nonce PENDING lookup
-- cannot double-issue) and TPM_FAILED (verify/issue failure; not returned to
-- PENDING). The original status CHECK (V2) only allowed
-- PENDING/CONSUMED/EXPIRED/REVOKED, so a REAL /attest on PostgreSQL failed with
-- check_violation (SQLState 23514) before any device binding — surfaced by the
-- live #548 enrollment on a real Intel ADL fTPM (denetim PC) once the EK chain
-- validated. Add the two TPM statuses. Drop both the V2 named constraint and the
-- PG auto-named variant for robustness (DROP ... IF EXISTS, idempotent).
-- Rollback boundary (Codex 019f03e2): once TPM_IN_PROGRESS/TPM_FAILED rows exist,
-- rolling back to a pre-enum app version is unsafe in the JPA enum read path; this is a
-- forward-only widening. For large/active tables prefer NOT VALID + VALIDATE; endpoint_enrollments
-- is small/sparse so the in-place DROP+ADD is an acceptable hotfix.
ALTER TABLE endpoint_enrollments DROP CONSTRAINT IF EXISTS ck_endpoint_enrollments_status;
ALTER TABLE endpoint_enrollments DROP CONSTRAINT IF EXISTS endpoint_enrollments_status_check;
ALTER TABLE endpoint_enrollments ADD CONSTRAINT ck_endpoint_enrollments_status
    CHECK (status IN ('PENDING','CONSUMED','EXPIRED','REVOKED','TPM_IN_PROGRESS','TPM_FAILED'));
