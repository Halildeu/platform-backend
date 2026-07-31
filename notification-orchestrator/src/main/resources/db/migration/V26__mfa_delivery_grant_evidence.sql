-- Faz 22 Sec / gitops#3212 — server-controlled evidence that ONE delivery was
-- authorised by auth-service as an authentication challenge.
--
-- Why columns and not the client-writable `metadata` map: the authz decision
-- is taken at the trust boundary (intent submit, where the grant JWT is
-- verified) but ENFORCED later, in the asynchronous dispatch worker, which
-- never sees the request. Storing the outcome in a field the submitter can
-- also write would make mislabelling a bypass. These columns are written only
-- by the verifier path (Codex 019fb825).
--
-- The dispatch worker skips ONLY the recipient-tuple check, and only when
-- every column below matches the delivery it is about to make and the window
-- has not closed. Template allow-list, channel limits, rate limits,
-- idempotency, audit and fail-closed behaviour are untouched.

ALTER TABLE notify.notification_intent
    ADD COLUMN delivery_class VARCHAR(32),
    ADD COLUMN grant_jti VARCHAR(64),
    ADD COLUMN grant_subject VARCHAR(128),
    ADD COLUMN grant_recipient_hash VARCHAR(128),
    ADD COLUMN grant_deliver_before TIMESTAMPTZ;

-- Replay guard: a grant authorises exactly one intent. A second intent
-- presenting the same jti fails at INSERT rather than being silently
-- accepted with a stale authorisation.
CREATE UNIQUE INDEX uq_notification_intent_grant_jti
    ON notify.notification_intent (grant_jti)
    WHERE grant_jti IS NOT NULL;

-- Fail-closed shape: the evidence is all-or-nothing. A row carrying a
-- delivery_class must carry the whole binding, and a row carrying part of the
-- binding must carry the class — a half-written evidence set can never be
-- interpreted as authorisation.
ALTER TABLE notify.notification_intent
    ADD CONSTRAINT ck_notification_intent_grant_complete CHECK (
        (delivery_class IS NULL
            AND grant_jti IS NULL
            AND grant_subject IS NULL
            AND grant_recipient_hash IS NULL
            AND grant_deliver_before IS NULL)
        OR
        (delivery_class = 'AUTHENTICATION_CHALLENGE'
            AND grant_jti IS NOT NULL
            AND grant_subject IS NOT NULL
            AND grant_recipient_hash IS NOT NULL
            AND grant_deliver_before IS NOT NULL)
    );

COMMENT ON COLUMN notify.notification_intent.delivery_class IS
    'gitops#3212: AUTHENTICATION_CHALLENGE when auth-service authorised this exact delivery; NULL for ordinary notifications.';
COMMENT ON COLUMN notify.notification_intent.grant_recipient_hash IS
    'Org-namespaced HMAC of the granted recipient, computed here — never supplied by the caller.';
