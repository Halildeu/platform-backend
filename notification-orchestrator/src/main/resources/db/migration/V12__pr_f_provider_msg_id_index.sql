-- Faz 23.4 PR-F — provider_msg_id partial unique index for DLR lookup.
--
-- Codex iter-1 P1.5 absorb (PR #85 thread 019e00d9):
--   - DLR ingest sıkça findFirstByProviderMsgId çağırır (production scale
--     10k SMS/min); index olmadan tablo scan
--   - (provider, provider_msg_id) tuple unique olmalı: aynı provider +
--     aynı job id iki ayrı delivery row'una asla atanmaz; eğer atanırsa
--     veri integrity hatası — UNIQUE constraint detects
--
-- Partial WHERE provider_msg_id IS NOT NULL: PENDING / RETRY (henüz adapter
-- çağrılmamış) row'lar provider_msg_id NULL taşır; index'e dahil değil
-- (storage tasarrufu + index hız).

CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_provider_msg_id
    ON notify.notification_delivery (provider, provider_msg_id)
    WHERE provider_msg_id IS NOT NULL;

COMMENT ON INDEX notify.uq_delivery_provider_msg_id IS
    'Faz 23.4 PR-F: DLR ingest fast path; (provider, provider_msg_id) '
    'tuple unique. Partial: PENDING/RETRY rows excluded (no provider_msg_id).';
