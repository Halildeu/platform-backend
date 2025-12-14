# Gateway & Servis JWT Doğrulama Stratejisi

Client credentials JWT geçişi ile birlikte gateway ve servislerde uygulanacak doğrulama, fail-fast ve degrade planı.

## 1. JWT Doğrulama Gereksinimleri
- Algoritma: yalnız `RS256`; `alg` header kontrolü zorunlu.
- `iss`: `https://auth.{env}.corp`
- `aud`: hedef servis adı (`user-service`, `permission-service` vb.)
- `exp` toleransı: 30 sn clock skew.
- `kid`: JWKS’ten eşleşmeli; eşleşmezse token reddedilir.
- Claims:
  - `svc`: çağrıyı yapan servis kimliği.
  - `env`: token ortamı (`prod`, `staging`).
  - `perm`: gerekli scope/policy (opsiyonel).

## 2. Gateway Akışı
1. Request header: `Authorization: Bearer <token>`
2. Gateway JWT filter:
   - JWKS cache (5 dk TTL) + background refresh.
   - Token doğrulama; başarısız ise HTTP 401 (invalid) veya 403 (claim mismatch).
3. Downstream servis isteklerine `X-Service-Identity` ve `X-Correlation-Id` başlıkları eklenir.
4. Audit log’a her reddedilen token için `svc`, `reason`, `correlationId` yazılır.

### Vault Bağımlılığı
- Gateway sadece JWKS okuması yapar; Vault erişimi gerekmiyor.
- Vault kaynaklı secret eksikliğinde (ör. client secret yoksa) token alamayan servisler Gateway’e ulaşamayacak; alarm eşikleri devrede.

## 3. Servis Tarafı Doğrulama
- Her servis, gelen iç çağrıları Spring Security filter ile doğrular.
- JWKS HTTP cache + memcache (Caffeine).
- Token çözülemezse `401`; claim mismatch (`env != currentEnv`, `svc` beklenen listede değil) → `403`.
- Fault tolerance:
  - JWKS fetch başarısızsa 30 sn exponential backoff ile yeniden dene.
  - 3 başarısız denemede circuit breaker açık → 5 dk 503 döner (fail-fast).
  - Circuit açıkken Prometheus metriki `jwt_validation_circuit_open` tetiklenir.

## 4. Degrade Planı (Vault/Token Servisi Erişilemezse)

| Bileşen | Durum | Davranış | Alarm |
|---------|-------|----------|-------|
| Gateway | JWKS alınamıyor | 503 (Gateway Timeout) + fallback sayfası | PagerDuty: `AUTH-JWKS-DOWN` |
| Permission Service | Token yok (Auth servisten alamıyor) | `/permissions/**` → 503, `/health` → 200 (degrade) | OpsGenie `PERM-TOKEN-FAIL` |
| User Service | Token yok | Yazma endpointleri 503, okuma endpointleri read-only cache (15 dk) | `USER-READONLY-MODE` |
| Notification Service | Token yok | Kuyruğa alınan mesajları beklet (retry 10 dk), sync API 503 | `NOTIF-QUEUE-BACKLOG` |

### Alarm Eşikleri
- JWKS fetch hata sayısı 5 dk’da 3’den fazla → alarm.
- Token alma isteği 10 dk içinde başarıya ulaşmazsa alarm.
- 503 oranı %5’i geçerse SLO ihlali uyarısı.

## 5. Test Senaryoları
1. Geçerli JWT → 200.
2. Expired JWT → 401, audit log kaydı.
3. Claim mismatch (`env=staging` iken prod isteği) → 403.
4. JWKS endpoint kapalı → circuit breaker açılır, 503 döner, alarm tetiklenir.
5. Vault secret silinmiş → servis token alamaz, degrade stratejisi çalışır.
6. Correlation ID log’larda uçtan uca izlenebilir (gateway, servis, audit).

## 6. İzleme ve Loglama
- Prometheus metrikleri:
  - `jwt_validation_success_total`
  - `jwt_validation_failure_total{reason=...}`
  - `jwks_fetch_duration_seconds`
  - `service_degrade_mode{service=...}`
- Log formatı (`JSON`):
  ```json
  {
    "timestamp": "...",
    "corrId": "...",
    "service": "permission-service",
    "event": "jwt_validation_failure",
    "reason": "kid_not_found"
  }
  ```
- Vault audit loglarına servis token talepleri özel `role` etiketiyle yazılır.

## 7. Uygulama Adımları
1. Gateway’de JWT filter modülü ekle, feature flag ile kontrol et (`JWT_ENFORCEMENT_ENABLED`).
2. Servisler için ortak library (starter) yaz; JWKS client + circuit breaker içerir.
3. Observability pipeline’ı (ELK/Tempo) correlation ID ile güncelle.
4. Smoke test script’i (`scripts/test-jwt-flow.sh`) ile degrade senaryolarını otomatik çalıştır.
5. Runbook’a (Faz 3) test raporlarını referans olarak ekle.

---
**Sonraki Adım:** Spring Cloud Vault entegrasyonu ve secret bulunamadığında fail-fast/log/alert eşiği dokümantasyonunu güncelleyin.
