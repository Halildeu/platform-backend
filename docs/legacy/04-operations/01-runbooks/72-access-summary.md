# Access & Audit — Sprint Özeti

## Nerede Kaldık (Tamamlananlar)
- FE: SSRM sort param üretimi (`field,dir;field2,dir2`) eklendi.
- BE: Çoklu ORDER BY + advancedFilter (whitelist) uygulandı (users listesi).
- BE: CSV export (stream) eklendi — `GET /api/users/export.csv` (bellek sabit, filtre/sort uyumlu).
- Dokümanlar: API (users/auth/audit-events/common-headers), Security (advanced-filter-whitelist, export-security), Observability (OTEL + panolar), Roadmap güncel.

## Tamamlanan Öncelikli Adımlar (2025-11-29)
1) **Audit bağlama (SP2‑1)**
   - permission-service’deki tüm mutasyon yanıtları `auditId` ile dönüyor; `PermissionControllerV1` ve `AccessControllerV1` için yeni WebMvc testleri `permission-service/src/test/java/com/example/permission/controller/PermissionControllerV1Test.java` ve `AccessControllerV1Test.java` dosyalarında bu sözleşmeyi doğruluyor.
   - user-service `PUT /api/users/{id}` yanıtı `UserResponse.auditId` alanını dolduruyor (`user-service/src/main/java/com/example/user/controller/UserController.java:608`); FE tarafında `notify` yardımcıları audit deep-link’i `/admin/audit?event=<auditId>` formatında üretiyor (`packages/ui-kit/src/lib/notify/notify.ts:21`).
2) **Audit Events endpoint**
   - `permission-service/src/main/java/com/example/permission/controller/AuditEventController.java` GET/list/export/live uçlarını sağlıyor; `id` parametresi tek kaydı 404 garantisiyle döndürüyor ve `AuditEventControllerTest` içinde kapsanıyor.
   - Export ve SSE uçları audit akışını FE/Audit MFE’ye bağlamak için kullanılıyor; runbook’taki filtre seti `extractFilters` yardımıyla destekleniyor.
3) **Export guardrails (gateway)**
   - `api-gateway/src/main/java/com/example/apigateway/security/ExportGuardFilter.java` CSV/SSE rotalarını rate-limit edip `X-PII-Policy: mask` header’ı ekliyor; `ExportGuardFilterTest` dosyası rate-limit, header ve bypass senaryolarını kapsıyor.
4) **V1 Activation MutationAck**
   - `/api/v1/users/{id}/activation` uç noktası artık `UserMutationAckDto` döndürüyor; `user-service/src/main/java/com/example/user/controller/UserControllerV1.java` aktör kullanıcıyı `requireCurrentUser()` ile alıyor ve `UserAuditEventService.recordActivationEvent` tetikliyor. Yanıtın `auditId` değeri FE’de `/admin/audit?event=<id>` deep-link’iyle kullanılmak üzere döndürülüyor.
   - `UserControllerV1Test.updateActivation_returnsAckWithAudit` testi, yanıt ve `UserAuditEventRepository` kaydı üzerinden sözleşmeyi doğruluyor; Users API dokümanı (docs/03-delivery/api/users.api.md) MutationAck örneğiyle güncellendi.
5) **AccessControllerV1 Tiplenmiş DTO’lar**
   - `permission-service/src/main/java/com/example/permission/controller/AccessControllerV1.java` Map döndürmek yerine `RoleCloneResponseDto` ve `BulkPermissionsResponseDto` kullanıyor; böylece schema SSOT `docs/03-delivery/api/permission.api.md` ile bire bir uyumlu hale geldi.
   - Yeni DTO’lar `dto/v1` paketine eklendi ve `AccessControllerV1Test` JSON yanıtlarının auditId alanını doğrulayacak şekilde güncellendi.
6) **Audit Events Filter Guardrail**
   - `docs/04-operations/02-monitoring/tempo-loki-dashboards.md` dosyasına audit filtre kullanım oranını takip eden yeni panel ve alarm eşikleri eklendi; ilgili alert manifesti `scripts/perf/grafana/provisioning/alerting/audit-filter-usage-loki.yml`.
   - Guardrail: `filter[` içermeyen `/api/audit/events` çağrıları oranı 5 dakika boyunca %20’nin altına düşerse WARNING, %10’un altına düşerse CRITICAL; incident durumunda flag manifest raporu incelenip gerekli cleanup yapılır.
7) **Users Activation audit CTA (shell notify)**
   - `frontend/apps/mfe-users` içindeki durum toggling akışı `shellServices.notify.push` ile auditId bilgisini Notification Center’a taşıyor; toast metinleri `users.notifications.activation.description` i18n key’iyle standardize edildi.
   - `docs/03-delivery/guides/manifest-simplification-audit-cta.md` örnekleri yeni shell notify kontratıyla güncellendi; runbooktaki “Önerilen” adım kapandı.
8) **Audit Filter Guardrail CI entegrasyonu**
   - `scripts/ci/canary/guardrail-check.mjs` script’i `audit_filter_usage_pct` alanını zorunlu hale getiriyor; `--audit-filter-usage` parametresi veya `GUARDRAIL_AUDIT_FILTER_USAGE_MIN_PCT` env değişkeni ile eşik override edilebiliyor.
   - Örnek metrics dosyaları (`scripts/ci/canary/samples/*.json`) audit filter kullanım yüzdesiyle güncellendi ve `docs/03-delivery/02-ci/04-canary-pipeline.md` dökümanı yeni guardrail’i açıklıyor.

## Önerilen Yeni Sıradaki Adımlar
Şu anda tamamlanmayı bekleyen yeni madde yok; yeni ihtiyaçlar doğduğunda bu bölüm güncellenecek.

## Kod Puanları (Dokunulan Yerler)
- permission-service: Controller/Service — assign/update/revoke → audit kaydı + response `auditId`
- user-service: UpdateUser → audit kaydı + response `auditId`
- audit endpoint: Yeni Controller (permission-service içinde) — `docs/03-delivery/api/audit-events.api.md`’ye uyum

## Test Checklist
- FE: sort → Network’te `sort=` paramı; liste sıralı
- Jest: sort format testi
- Cypress: UI sort → API sort doğrulaması
- BE: sort/advancedFilter → çoklu ORDER BY, bozuk JSON/izinsiz operatör 400
- Export: `/api/users/export.csv` stream; büyük dataset bellek sabit
- Audit: Mutasyon yanıtında `auditId`; `/api/audit/events?id=<auditId>` event’ı döner

## Dokümantasyon Kuralları
- API davranışı değiştiyse `docs/03-delivery/api/*.md` güncelle
- Güvenlik etkisi varsa mimari (`docs/01-architecture/04-security/**/*`) ve compliance (`docs/agents/**/*`) dokümanlarını güncelle
- İlgili Story/acceptance ve PROJECT_FLOW notlarını güncelle
