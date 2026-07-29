-- Faz 35 ES-403 — one active subscription per organisation and product (#885).
--
-- Two active rows for the same product carry nothing extra and make revocation ambiguous:
-- closing one leaves the capability standing, so a withdrawal that looked complete would not
-- be. For SUBJECT_REVEAL that is the difference between a capability being taken back and
-- appearing to be.
--
-- Enforced as a partial unique index rather than a script-side check, because the check has
-- to hold for every writer — including the next one, which has not been written yet. Revoked
-- rows are deliberately outside the index: history is allowed to repeat, only the open window
-- is unique.
--
-- Postgres-only lane: partial indexes are not portable, and the H2 source tests exercise the
-- application contract rather than this backstop.

CREATE UNIQUE INDEX ethics_org_subscription_single_active_idx
    ON ethics_org_subscription (org_id, product_id)
    WHERE active;

COMMENT ON INDEX ethics_org_subscription_single_active_idx IS
    'At most one open subscription window per organisation and product. Revoked rows are '
    'excluded so the same product can be sold again later; what cannot happen is two live '
    'grants, which would make a revocation silently incomplete.';
