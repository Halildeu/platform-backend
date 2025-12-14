# Story E07-S01 – i18n & Erişilebilirlik Süreçleri v1.0

- Epic: E07 – Globalization & Accessibility  
- Story Priority: 710  
- Tarih: 2025-11-29  
- Durum: In Progress

## Kısa Tanım

TMS tabanlı sözlük paketleme pipeline’ını, pseudolocale/fallback testlerini ve erişilebilirlik süreç standardını kurarak manifest tabanlı ekranlar için i18n + WCAG-AA hedeflerini güvence altına almak.

## İş Değeri

- TR/EN/DE/ES ve yeni diller için çeviri yönetimi otomatik ve izlenebilir hale gelir.
- Eksik/yanlış çeviriler pseudolocale ve telemetry ile erken yakalanır.
- A11y süreçleri standardize edilerek ekran okuyucu, klavye erişimi ve kontrast sorunları her akışta yönetilir.

## Bağlantılar (Traceability Links)

- SPEC: `docs/05-governance/06-specs/SPEC-E07-S01-GLOBALIZATION-A11Y-V1.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E07-S01-Globalization-and-Accessibility.acceptance.md`  
- ADR: ADR-008 (i18n & Sözlük Paketleme), ADR-014 (Accessibility Process Standardı)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`

## Kapsam

### In Scope
- TMS (Tolgee/Weblate) → `@mfe/i18n-dicts` paketlerine giden i18n pipeline’ının tanımlanması.
- Pseudolocale (örn. `zz-ZZ`) ile temel ekranların görsel olarak bozulmadan test edilmesi.
- Fallback stratejisi (TR → EN vb.) ve eksik key telemetry’sinin uygulanması.
- Her akış için A11y checklist’inin hazırlanması: SR turu (NVDA/VoiceOver), keyboard trap testi, kontrast ölçümleri.
- CI pipeline’ına axe-core veya benzeri otomatik a11y kontrollerinin eklenmesi.

### Out of Scope
- Tüm var olan ekranların tek seferde yeni i18n/a11y standardına taşınması (kademeli yapılır).
- Tasarım kitindeki tüm bileşenler için kapsamlı A11y rehberi (ayrı design system çalışması).

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| TMS → @mfe/i18n-dicts sözlük pipeline’ının tanımlanması     | 2025-11-29   | 2025-11-29    | 2025-12-06   | 2025-12-06  |
| Pseudolocale ve fallback stratejisinin uygulanması          | 2025-12-06   | 2025-12-06    | 2025-12-06   | 2025-12-06  |
| Eksik key telemetry’sinin ve eşiklerinin eklenmesi          | 2025-12-06   | 2025-12-06    | 2025-12-06   | 2025-12-06  |
| Akış bazlı A11y checklist’inin tanımlanması ve uygulanması | 2025-12-06   | 2025-12-06    | 2025-12-06   | 2025-12-06  |
| CI pipeline’ına axe-core veya benzeri a11y kontrollerinin eklenmesi| 2025-12-06 |               |              |             |
| i18n/a11y süreçlerinin handbook ve dokümantasyona işlenmesi | 2025-12-06   | 2025-12-06    | 2025-12-06   | 2025-12-06  |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

**Kural:**
- İlk tarih “Ready” → görev hazır  
- İkinci tarih “InProgress” → çalışma başladı  
- Üçüncü tarih “Review” → kod kontrol aşamasında  
- Son tarih “Done” → tamamen tamamlandı  
- Soldaki sütun dolmadan sağdaki sütun doldurulmaz (Ready → InProgress → Review → Done akışına uyulur).

## Fonksiyonel Gereksinimler

1. Yeni/updated sözlükler TMS’ten CI pipeline’ına alınmalı ve paketlere dönüştürülmelidir.
2. Eksik/yanlış çeviri durumları telemetry’de ölçülmeli; eksik key oranı < %1 hedeflenmelidir.
3. A11y checklist’i her akış döngüsünde en az bir kritik akış için tamamlanmalıdır.
4. CI a11y kontrolleri kırmızı ise ilgili PR merge edilmemelidir.

## Non-Functional Requirements

- Sözlük paketleme pipeline’ı build sürelerini aşırı artırmamalıdır.
- A11y testleri mümkün olduğunca deterministik olmalı; flaky durumlar hızlıca ele alınmalıdır.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/E07-S01-Globalization-and-Accessibility.acceptance.md`

## Definition of Done

- [ ] i18n & a11y acceptance maddeleri sağlanmış olmalı.  
- [ ] Acceptance dosyasındaki checklist (`docs/05-governance/07-acceptance/E07-S01-Globalization-and-Accessibility.acceptance.md`) tam karşılanmış olmalı.  
- [ ] ADR-008/014 kararları uygulanmış olmalı.  
- [ ] Kod, `docs/00-handbook/NAMING.md` ve stil kurallarına uyumlu olmalı.  
- [ ] Kod review’dan onay almış olmalı.  
- [ ] i18n-smoke ve a11y testleri (axe-core vs) yeşil olmalı.  
- [ ] `docs/05-governance/PROJECT_FLOW.md` üzerinde Son Durum ✔ Done olarak işlenmiş olmalı.

## Notlar

- TMS ve sözlük pipeline’ı genişledikçe yeni diller için acceptance kapsamı artırılmalıdır.
- TMS → `@mfe/i18n-dicts` akışı için `frontend/.env.i18n.example` ve `docs/03-delivery/guides/i18n-tms.md` dosyaları referans alınır; `npm run i18n:pull` komutu bu ortam değişkenleriyle çalışır, lokal doğrulama için `--local-only` parametresi kullanılabilir.
- Axe smoke testi için `npm run quality:audit:build` sonrası `npm run test:a11y -- --chromedriver-path node_modules/.bin/chromedriver --serve-url http://localhost:4173` kombinasyonu kullanılmalı; chrome driver veya serve-url olmadan CLI `ENOENT` ve `net::ERR_ADDRESS_UNREACHABLE` hataları veriyor (QA-02 notu).

## Dependencies

- ADR-008 – i18n & Sözlük Paketleme.
- ADR-014 – Accessibility Process Standardı.

## Risks

- i18n/a11y konularının “nice-to-have” olarak kalması ve süreçlerin zamanla terk edilmesi.
- TMS ve kod tabanındaki key setlerinin uyuşmazlığı.

## Flow / Iteration İlişkileri

| Flow ID   | Durum    | Not                                                             |
|-----------|---------|-----------------------------------------------------------------|
| Flow-03   | Planned | i18n sözlük pipeline’ı ve a11y süreç standardı için ilk uygulama dalgası. |
