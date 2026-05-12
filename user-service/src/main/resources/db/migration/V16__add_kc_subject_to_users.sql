-- V16: Add `kc_subject` column to `users` table for Keycloak user identifier
-- (Codex thread 019e1bed AGREE — User Impersonation UI 1.0 backend
-- authoritative subject resolution).
--
-- Context: ImpersonationController previously required clients to pass
-- `targetSubject` (KC user UUID) in the StartSessionRequest payload, but the
-- admin UI has no way to know that UUID — it had to be typed manually,
-- making the feature effectively unusable for real admins. Storing
-- `kc_subject` alongside the platform user record (1:1 with the Keycloak
-- user_entity.id for that email) lets:
--
--   1. user-service expose `kcSubject` in UserResponse so the frontend can
--      auto-fill the impersonation request without operator action.
--   2. auth-service `ImpersonationController` make `targetSubject` optional
--      and resolve it server-side from `targetUserId` via the
--      `UserServiceClient.findUserById` lookup.
--
-- The column is nullable to allow Flyway to add it without an immediate
-- backfill (operator runbook handles existing users). Once provisioning
-- writes `kc_subject` on every user create flow + backfill is complete,
-- a follow-up migration can make it NOT NULL.
--
-- Index: partial unique on non-null values so the same KC subject cannot
-- map to two platform users (defense-in-depth — KC enforces UUID
-- uniqueness, but this guards against drift if rows are restored from a
-- different realm).

ALTER TABLE users
    ADD COLUMN kc_subject VARCHAR(64);

CREATE UNIQUE INDEX idx_users_kc_subject
    ON users (kc_subject)
    WHERE kc_subject IS NOT NULL;
