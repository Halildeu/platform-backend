-- Faz 23.1 PR4 iter-1 absorb (Codex 019dfa47 review):
--
--   1. claim_token (UUID per worker cycle) — multi-pod claim isolation
--      (P0 #2: pre-fix `findByStatusAndProcessingOwner` her owner'in tum
--       PROCESSING intent'lerini görüyordu → claim cycle'in icindeki yeni
--       claim'leri ile karisma. claim_token UUID per cycle ile sadece bu
--       cycle'in claim ettigi rowlar fetch edilir.)

ALTER TABLE notify.notification_intent
    ADD COLUMN claim_token VARCHAR(64);

ALTER TABLE notify.notification_delivery
    ADD COLUMN claim_token VARCHAR(64);

CREATE INDEX idx_intent_claim_token
    ON notify.notification_intent (claim_token)
    WHERE claim_token IS NOT NULL;

CREATE INDEX idx_delivery_claim_token
    ON notify.notification_delivery (claim_token)
    WHERE claim_token IS NOT NULL;

COMMENT ON COLUMN notify.notification_intent.claim_token IS
    'Worker cycle UUID (Codex 019dfa47 iter-1 P0 absorb). Set on claim, cleared '
    'on terminal/retry-outstanding/lease-recovery. Multi-pod claim isolation: '
    'pod fetches only rows it claimed in this cycle (not all PROCESSING by '
    'owner).';
