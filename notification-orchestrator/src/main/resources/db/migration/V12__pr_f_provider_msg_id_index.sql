-- Faz 23.4 PR-F — provider_msg_id partial unique index for DLR lookup.
--
-- Codex iter-1 P1.5 + iter-2 P1.2 absorb (PR #85 thread 019e00d9):
--   - DLR ingest sıkça findFirstByProviderMsgId çağırır (production scale
--     10k SMS/min); index olmadan tablo scan
--   - provider_msg_id zaten provider prefix taşıyor (örn. "netgsm-{jobid}"),
--     ayrı provider column'una göre filtrelenmesi gerekmez
--   - Single-column UNIQUE index hem DLR query'sinin (WHERE provider_msg_id=?)
--     fast path olması için doğru leading column hem de aynı msg id'nin iki
--     row'a atanmasını engeller (data integrity)
--
-- Partial WHERE provider_msg_id IS NOT NULL: PENDING / RETRY (henüz adapter
-- çağrılmamış) row'lar provider_msg_id NULL taşır; index'e dahil değil
-- (storage tasarrufu + index hız).

CREATE UNIQUE INDEX IF NOT EXISTS uq_delivery_provider_msg_id
    ON notify.notification_delivery (provider_msg_id)
    WHERE provider_msg_id IS NOT NULL;

COMMENT ON INDEX notify.uq_delivery_provider_msg_id IS
    'Faz 23.4 PR-F: DLR ingest fast path; provider_msg_id (already provider-'
    'prefixed) globally unique. Partial: PENDING/RETRY rows excluded (NULL).';
