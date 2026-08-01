-- V18: Add `kc_username` to `users` — the Keycloak login name, cached for display.
-- (gitops#3291. Follows the `kc_subject` precedent from V16: a Keycloak fact
-- kept alongside the platform user record so the panel can show it without a
-- per-row admin-API call.)
--
-- Why the panel needs a column at all: until gitops#3245 every username *was*
-- an e-mail address, so the `email` column doubled as the login identity by
-- coincidence. Renaming the operator accounts to plain handles (`halildeu`,
-- `halil.kocoglu`, …) turned that coincidence into a real gap — the users grid
-- could no longer say which login a row belongs to. `User.getUsername()` does
-- not help: it returns `email`, because it implements Spring Security's
-- `UserDetails` contract, not Keycloak's notion of a username.
--
-- Why cached rather than read-through: the Keycloak admin API has no bulk
-- get-by-ids, so rendering a 25-row page would cost 25 sequential calls and
-- couple grid latency to Keycloak availability. Worse, a value that lives only
-- in Keycloak cannot take part in the grid's SERVER-side sort and filter, which
-- resolve against JPA fields. Storing it keeps the column first-class.
--
-- Index is deliberately NOT unique. Keycloak already enforces per-realm
-- uniqueness, so a constraint here buys nothing — but it could actively harm:
-- the reconcile converges row by row, so a legitimate Keycloak-side rename
-- chain (A takes the handle B just gave up) would transiently collide and the
-- cache could never catch up. Drift is a query, not a constraint.

ALTER TABLE users
    ADD COLUMN kc_username VARCHAR(255);

CREATE INDEX idx_users_kc_username
    ON users (kc_username)
    WHERE kc_username IS NOT NULL;
