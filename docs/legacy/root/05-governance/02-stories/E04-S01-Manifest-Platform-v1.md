# Story E04-S01 – Manifest & Sözleşme Platformu v1.0

- Epic: E04 – Platform Manifest & Contracts  
- Story Priority: 410  
- Tarih: 2025-12-05  
- Durum: Done

## Kısa Tanım

Remote manifest servislerini, PageLayout manifest modelini ve manifest/ShellServices şema paketlerini hayata geçirerek manifest tabanlı platformun ilk sürümünü tamamlamak.

## İş Değeri

- Remotelar ve sözlükler manifest üzerinden yönetilir; sürüm uyumsuzlukları erken yakalanır.
- SRI + CSP ile supply chain riskleri azaltılır; manifest hash değişiklikleri kontrollü yapılır.
- Manifest ve ShellServices kontratlarının CI’da otomatik doğrulanmasıyla prod’da şema hataları engellenir.

## Bağlantılar (Traceability Links)

- SPEC: `docs/05-governance/06-specs/SPEC-E04-S01-PLATFORM-MANIFEST-V1.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E04-S01-Manifest-Platform-v1.acceptance.md`  
- ADR: ADR-001 (Remote Manifest & SRI), ADR-002 (PageLayout & Manifest Modeli), ADR-007 (Contract & Schema Gating)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`

## Kapsam

### In Scope
- Versiyonlanmış manifest JSON şemasının tanımı ve gateway’de servis edilmesi.
- Manifest içeriğinde remote adı, URL, SRI hash’i, semver aralığı ve temel meta alanlarının zorunlu hale getirilmesi.
- PageLayout/PageManifest modelinin Zod/JSON Schema ile tanımlanması ve en az iki ekranın yalnız manifest yazarak ayağa kaldırılması.
- Manifest ve ShellServices tip paketlerinin (TS/JSON Schema) oluşturulması ve CI contract test adımına bağlanması.

### Out of Scope
- Canary rollout adımları ve DR/HA topolojisi (E05 kapsamında).
- FE tarafındaki tüm sayfaların bir seferde manifest’e taşınması (kademeli yapılır).

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Manifest JSON şemasının tasarlanması ve versiyonlanması     | 2025-12-05   | 2025-12-05    | 2025-12-06   | 2025-12-06  |
| Manifest servis endpoint’inin gateway’de expose edilmesi     | 2025-12-05   | 2025-12-05    | 2025-12-06   | 2025-12-06  |
| PageLayout/PageManifest modelinin implementasyonu            | 2025-12-05   | 2025-12-05    | 2025-12-06   | 2025-12-06  |
| En az iki ekranın yalnız manifest ile oluşturulması          | 2025-12-05   | 2025-12-05    | 2025-12-06   | 2025-12-06  |
| Manifest ve ShellServices tip paketlerinin hazırlanması      | 2025-12-05   | 2025-12-05    | 2025-12-06   | 2025-12-06  |
| Manifest/kontrat testlerinin CI pipeline’ına eklenmesi       | 2025-12-05   | 2025-12-05    | 2025-12-06   | 2025-12-06  |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

**Kural:**
- İlk tarih “Ready” → görev hazır  
- İkinci tarih “InProgress” → çalışma başladı  
- Üçüncü tarih “Review” → kod kontrol aşamasında  
- Son tarih “Done” → tamamen tamamlandı  
- Soldaki sütun dolmadan sağdaki sütun doldurulmaz (Ready → InProgress → Review → Done akışına uyulur).

## Fonksiyonel Gereksinimler

1. Shell açılışında manifest en fazla bir ek HTTP isteği ile yüklenmeli ve p95 ≤ 200 ms hedefini karşılamalıdır.
2. Her remoteEntry için SRI doğrulaması yapılmalı; hash uyuşmazlığında remote mount edilmemelidir.
3. Manifest ve PageLayout şemaları için contract test step’i CI pipeline’ında zorunlu olmalı; uyumsuzlukta build fail etmelidir.
4. En az iki ekran yalnız manifest yazarak (ekstra özel kod olmadan) PageLayout üzerinden render edilmelidir.

## Non-Functional Requirements

- Manifest doğrulaması Shell açılış süresini belirlenen bütçenin dışına çıkarmamalıdır.
- Şema değişiklikleri semver major/minor kurallarına uygun versiyonlanmalıdır.

## Flow / Iteration İlişkileri

| Flow ID   | Durum    | Not                                             |
|-----------|---------|-------------------------------------------------|
| Flow-03   | Planned | Manifest & sözleşme platformu için ilk uygulama dalgası. |

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/E04-S01-Manifest-Platform-v1.acceptance.md`

## Definition of Done

- [x] Manifest JSON şeması, SRI ve contract test acceptance maddeleri sağlanmış olmalı.  
- [x] Acceptance dosyasındaki checklist (`docs/05-governance/07-acceptance/E04-S01-Manifest-Platform-v1.acceptance.md`) tam karşılanmış olmalı.  
- [x] İlgili ADR kararları (ADR-001/002/007) uygulanmış olmalı.  
- [x] Kod, `docs/00-handbook/NAMING.md` ve stil kurallarına uyumlu olmalı.  
- [x] Kod review’dan onay almış olmalı.  
- [x] Contract/CI testleri yeşil olmalı.  
- [x] `docs/05-governance/PROJECT_FLOW.md` üzerinde Son Durum ✔ Done olarak işlenmiş olmalı.

## Notlar

- Manifest ve şema değişikliklerinde CI kırmızı oranı artarsa şema evrimi stratejisi (semver, migration) gözden geçirilmelidir.

## Çalışma Planı (Sıralı)
1) Manifest JSON şeması (versiyonlu) + örnek manifest (SRI/hash, semver)  
2) Manifest endpoint’inin gateway’de expose edilmesi (örn. `/manifest/v1/manifest.json`)  
3) PageLayout/PageManifest modelinin TS/Zod + JSON Schema ile tanımlanması, tip paketleri (Manifest/ShellServices)  
4) En az iki ekranın yalnız manifest ile PageLayout üzerinden çalışır hale getirilmesi  
5) CI contract test adımı: manifest + ShellServices şemaları için merge-blocker step  
6) Acceptance/PROJECT_FLOW güncellemesi, kanıt notları, session-log

## İlerleme Notu (güncel)
- Manifest şeması ve örnek: `backend/manifest/manifest.schema.json` + `backend/manifest/examples/sample.manifest.json` eklendi (SRI + semver alanları içeriyor).
- Gateway’de statik manifest endpoint: `/manifest/v1/manifest.json` (api-gateway `static/manifest/v1/manifest.json`).
- PageLayout şeması: `backend/manifest/page-layout.schema.json` (layout + bileşen tanımı için temel alanlar).
- PageLayout örnekleri: `backend/manifest/examples/page-users.layout.json`, `page-access.layout.json` (iki ekran için manifest tanımı).
- Doğrulama script’i: `backend/scripts/manifest/validate.sh` (`npx ajv-cli` ile manifest + page-layout örnekleri doğrulanıyor).
- FE tipleri + client: Manifest/PageLayout tipleri `frontend/packages/shared-types` içinde; `packages/shared-http/src/manifest.ts` fetchManifest/fetchPageLayout fonksiyonları bu tiplerle çalışıyor.

## Dependencies

- ADR-001 – Remote Manifest & SRI.
- ADR-002 – PageLayout & Manifest Modeli.
- ADR-007 – Contract & Schema Gating.

## Risks

- Manifest ve şema değişikliklerinin koordinasyonsuz yapılması; CI’de sık kırılmalara yol açması.
- Manifest JSON’larının elle düzenlenmesi sonucu hash ve semver drift’i.
