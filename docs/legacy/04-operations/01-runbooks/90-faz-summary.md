# Vault Program Faz Özeti

## Faz 1 – Hızlı Güvence (Durum)
- Kod/dokümantasyon tamamlandı, pratik doğrulamalar beklemede:
  - Vault smoke testleri (TLS/audit/snapshot, rekey/unseal tatbikatı, secret path testi).
  - JWKS rotasyon tatbikatı, monitoring/alarm doğrulamaları.
  - Kill-switch raporu ve runbook imzaları.
- Checklist: `docs/faz1-acceptance-checklist.md`.

## Faz 2 – Olgunlaşma (Planlanan / Devam)
- Vault HA & auto-unseal (docs/vault-ha.md).
- Snapshot restore tatbikatı (docs/vault-restore-drill.md).
- PKI & mTLS (docs/vault-pki-mtls.md).
- DB Secrets Engine (docs/vault-db-secrets.md).
- JWT rotasyon planı (docs/jwt-rotation-plan.md).
- Secret rotasyon pipeline (docs/secret-rotation-pipeline.md).
- Hot reload stratejisi (docs/hot-reload-strategy.md).

## Faz 3 – Standart & Denetim (Planlanan)
- OPA policy-as-code (docs/opa-policy-gateway.md).
- Vault transit entegrasyonu (docs/vault-transit.md).
- Observability & security test otomasyonu (docs/observability-security-tests.md).
- Runbook güncellemeleri & kill-switch tamamlaması (docs/runbook-updates.md).
- Faz 3 imzalar sonrası final rapor.

## Sonraki Adımlar
1. Faz 1 checklist’teki tatbikat/imza maddelerini tamamla (Vault smoke, JWKS rotasyonu, kill-switch logu).  
2. Faz 2 görevleri için izleme/test oturumları planla ve raporları ilgili dokümanlara ekle.  
3. Faz 3 policy & security test planını yürürlüğe koyduktan sonra Confluence/CMDB güncellemelerini yap.  
4. Her faz için onaylayan kişilerin imzasını al ve `docs/runbook-updates.md` referansıyla arşivle.

---

## Ek Not (Access/Audit Kesişimi)
- Access/Audit modülleri için güncel akış özeti: `docs/04-operations/01-runbooks/72-access-summary.md`.
- Backend tarafındaki audit entegrasyon PR‑1 (mutasyon yanıtlarında `auditId`), PR‑2 (Audit Events API filtre/DoD) ve PR‑3 (Gateway export guardrails) tamamlandı.
- Reporting MFE’de Users/Access/Audit grid’leri gerçek API’lere bağlandı:
  - Users raporu `/api/users/all` + `/api/users/export.csv` parametreleriyle uyumlu; toolbar’dan CSV export aktif.
  - Access raporu `/api/access/roles` çıktısını filtre/sırala/paginasyonlayarak grid’e taşıyor; CSV export client-side.
  - Audit raporu `/api/audit/events` filtre seti (search/level/service/date range) + `/api/audit/events/export` (CSV/JSON) akışlarını kullanıyor, detay paneli before/after diff gösteriyor.
  - Grid varyant seçici shell’den sağlanan listeyle çalışıyor (`/api/variants?gridId=reports.*`), kullanıcı seçimi preference API’sine yazılıyor.

---

## Dev Çalışma Notları (Hızlı)

- Users seed (1200 kayıt) — Docker Compose ile çalışan Postgres için:
  - Eğer container isimleri `backend-…` ile başlıyorsa:
    - `PGPASSWORD=dev_password docker exec -i backend-postgres-db-1 psql -U dev_user -d users -f - < user-service/src/main/resources/db/migration/V4__seed_1200_users.sql`
  - Compose-native ve proje kökünden çalıştırmak isterseniz:
    - `PGPASSWORD=dev_password docker compose exec -T postgres-db psql -U dev_user -d users -f /tmp/seed.sql` + öncesinde `docker compose cp user-service/src/main/resources/db/migration/V4__seed_1200_users.sql postgres-db:/tmp/seed.sql`
  - Not: Seed idempotent (ON CONFLICT DO NOTHING). Toplam ~1203 kayıt (mevcuttaki + 1200).

- Login & Yetki (dev)
  - Admin kullanıcı: `admin@example.com / admin1234` (seed sonrasında mevcuttur).
  - Admin’e `companyId=1` bağlamında ADMIN rolü atandığında FE `permissions` içinde `VIEW_USERS` gelir ve `/admin/users` görünür.
  - FE guard, `VIEW_USERS` yoksa “Kullanıcı verilerini görmek için yetkiniz bulunmuyor.” uyarısı gösterir.
  - Dev kolaylığı: Permission-service geçici olarak ulaşılamazsa ADMIN için auth-service tarafında varsayılan izin seti enjekte edilir (yalnız geliştirme ortamı; üretimde kapatılacaktır).

- Variant-service RS256/JWKS
  - JWKS: `http://auth-service:8088/oauth2/jwks`, `issuer=auth-service`.
  - Audience hizalaması geçici olarak `user-service`; auth-service çoklu `aud` verdikten sonra `variant-service` kendi `aud` değeriyle doğrulamaya geçirilecektir.
  - Scope kaynağı permission-service’dir: `/api/v1/authz/user/{id}/scopes` çağrısından gelen PROJECT scope id’leri allowed set olarak kullanılır, JWT `scopes` claim’i devre dışıdır. Bu değişiklik auth-service → servisler arası RS256+aud / Vault tabanlı S2S akışını etkilemez.

- Shell rota alias’ları:
  - `GET /admin/access` artık `→ /access/roles` yönlendirilir (beklenen link ile uyum).
  - `GET /admin/audit` artık `→ /audit/events` yönlendirilir (deep‑link uyumu).

- Access FE bağlayıcı (adapter):
  - `mfe-access` artık uzaktan `/api/access/roles` uçlarını deniyor; ulaşamazsa mock veriye geri düşüyor.
  - Clone/Bulk işlemleri UI’da iyimser (optimistic) güncellenir; arka planda gerçek çağrıya çalışır. Backend hazır olduğunda aynı API ile `auditId` beslenecek.
  - Backend erişimi eklendi (permission-service):
    - GET `/api/access/roles` → rol + özet policy listesi (moduleKey/moduleLabel/level)
    - POST `/api/access/roles/clone` → yeni rol + `auditId`
    - PATCH `/api/access/roles/bulk-permissions` → güncellenen rol ID’leri + `auditId`

- Module Federation paylaşımı (kalıcı çözüm):
  - Host ve tüm remote MFE’lerde `react`, `react-dom`, `react-router`, `react-router-dom` singleton paylaşıma alındı — kılavuz: `frontend/docs/module-federation-sharing.md`.
  - Hızlı başlangıç için örnek webpack ayarları eklendi: `frontend/docs/examples/webpack/**`.
  - Geçici `MemoryRouter` fallback (AccessApp) MF paylaşımları tamamlandığında kaldırılacaktır.

- Audit MFE canlı akış (SSE):
  - Başlangıç durumu manifest flag ile uyumlu: `localStorage.setItem('feature:audit_feed_enabled','true')` → açar.
  - Uç: `GET /api/audit/events/live` (permission-service); bağlantı kurulamazsa polling’e geri düşer (grid refresh).

- RS256/JWKS durumu (permission-service):
  - Etkin: `SECURITY_JWT_JWK_SET_URI`, `SECURITY_JWT_USER_JWK_SET_URI`, `SECURITY_JWT_ISSUER=auth-service`, `SECURITY_JWT_AUDIENCE=permission-service`.
  - HS fallback tamamen kaldırıldı; yalnızca JWKS üzerinden doğrulama destekleniyor.
  - user-service: Hem kullanıcı JWT doğrulaması hem de Permission-service’e giden servis token’ları RS256/JWKS hattını kullanıyor (`serviceJwtEncoder` + `/oauth2/jwks`).
