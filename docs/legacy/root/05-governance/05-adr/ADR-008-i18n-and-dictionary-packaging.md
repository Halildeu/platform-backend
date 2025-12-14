# ADR-008 – i18n & Sözlük Paketleme

**Durum (Status):** Accepted  
**Tarih:** 2025-11-02  

**İlgili Dokümanlar (Traceability):**
- SPEC: `docs/05-governance/06-specs/SPEC-E07-S01-GLOBALIZATION-A11Y-V1.md`
- ACCEPTANCE: `docs/05-governance/07-acceptance/E07-S01-Globalization-and-Accessibility.acceptance.md`
- STORY: E01-S05-Login.md, E07-S01-Globalization-and-Accessibility.md
- İlgili ADR’ler: ADR-002, ADR-004, ADR-013
- STYLE GUIDE: (Varsa STYLE-I18N-001)

---

# 1. Bağlam (Context)

- Manifest tabanlı sayfalar çok dilli çalışmalı; metinler build’e gömülü olmamalı. 
- TMS (Tolgee/Weblate) aracılığıyla hazırlanan sözlüklerin güvenli ve geriye dönük uyumlu şekilde dağıtılması gerekiyor.
- Pseudolocale ve fallback testleri olmadan eksik çeviriler fark edilmiyor; KVKK/GDPR kapsamında PII redaction'a dikkat edilmeli.

---

# 2. Karar (Decision)

- Tüm metinler `key` tabanlı yönetilecek; build’e gömülü string olmayacak.
- TMS pipeline’ı: çeviri → onay → versiyonlanmış sözlük artefact’ı (namespace + semver) → Gateway/CDN’de yayın.
- `packages/i18n-dicts` içinde `npm run i18n:pull` (`i18n:pull:ci`) komutları ile TMS’ten artımlı çekim yapılacak. Endpoint: `GET {TMS_BASE_URL}/api/dicts/{locale}/{namespace}` + Bearer `TMS_API_TOKEN`, ETag/Last-Modified destekli. Locale/namespaceler TMS discovery (`/api/dicts/namespaces`) veya env fallback’i (`I18N_LOCALES`, `I18N_NAMESPACES`) üzerinden belirlenir. `dictionaryVersion` semver: patch=anahtar güncelle, minor=yeni locale/namespace, major=>%5 silme / kırıcı rename.
- Shell i18n çekirdeği sözlükleri ETag/TTL ile cache’leyecek; fallback (ör. `tr` → `en`) ve pseudolocale testleri zorunlu olacak (`npm run i18n:pseudo` → `packages/i18n-dicts/src/locales/pseudo/*`). Pseudolocale dosyaları repo dışı artefact olarak üretilir; CI drift kontrolü `i18n-smoke` workflow’unda yapılır.
- `i18n-smoke` GitHub Actions workflow’u (pull request, schedule, manuel) `npm run i18n:pseudo` + pseudo locale ile shell buildi + render smoke + missing key taraması çalıştırır; başarısızlık durumunda pipeline kırılır.
- Telemetry ile fallback/eksik anahtar oranı izlenecek (ADR-004); hedef < %1.
- Sözlükler fail-closed (uyumsuz sürüm blok) davranacak; son başarılı sürüme fallback (ADR-013) devreye girecek.

---

# 3. Alternatifler (Alternatives)

- Build’e gömülü çeviri dosyaları
- Sadece runtime JSON yükleme, TMS entegrasyonu olmadan

Bu seçenekler bakım ve uyum maliyetleri nedeniyle tercih edilmemiştir.

---

# 4. Gerekçeler (Rationale)

- TMS tabanlı sözlük yönetimi, çeviri sürecini standardize eder.
- Key tabanlı yaklaşım, UI değişmeden metin değiştirmeye izin verir.
- Pseudolocale ve telemetry ile eksik çeviriler erken fark edilir.

---

# 5. Sonuçlar (Consequences)

- Sözlük artefact yönetimi için ayrı pipeline (semver, CDN publish) gerekli.
- Çeviri süreci TMS üzerinden yürütülecek; manuel müdahaleler kısıtlanacak.
- Geliştiriciler manifestlerde `i18nKey` kullanmak zorunda; key adlandırma standardı dokümante edilecek.
- TMS erişimi için `TMS_BASE_URL`, `TMS_API_TOKEN` GitHub Secrets olarak tanımlanacak; env değişkenleri CI ve lokal geliştirme dokümanlarında referans verilecek.
- Pseudolocale smoke workflow’u UI’daki eksik anahtarları erken yakalayacak; build sürelerine küçük (~1-2 dk) ek yük getirir.

### Acceptance / Metrics

- Fallback/eksik anahtar telemetrisi < %1 (p95).
- Pseudolocale ve RTL testleri CI veya nightly pipeline’da yeşil.
- Sözlük paketleri semver ve ETag ile yönetiliyor; uyumsuz sürüm yüklenmiyor.

---

# 6. Uygulama Detayları (Implementation Notes)

- TMS ve CI entegrasyonları için endpoint ve auth konfigürasyonu merkezi olarak yönetilmelidir.
- Sözlük artefact’larının versiyonlama kuralları ekipçe paylaşılmalıdır.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-02  | Accepted | İlk sürüm, ADR-008 kararı kabul edildi |

---

# 8. Notlar

- Telemetry tarafındaki fallback/eksik anahtar raporlaması ADR-004 ile birlikte düşünülmelidir.
