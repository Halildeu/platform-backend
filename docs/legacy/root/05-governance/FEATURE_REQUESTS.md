# FEATURE_REQUESTS – Kullanıcı Talepleri Inbox

Bu doküman, kullanıcıdan/işten gelen talepleri hızlıca **toplamak ve izlemek** için hafif bir inbox görevi görür.  
Detaylı gereksinimler her zaman ilgili Story dokümanında tutulur; bu sayfa yalnızca özet ve izleme listesidir.

## 1. Kullanım Amacı

- Yeni bir talep geldiğinde:
  - Kısa özetini bu tabloya ekle,
  - Mümkünse hemen bir Epic/Story ile ilişkilendir,
  - Henüz Story yoksa `Story` sütununa “(henüz yok, draft açılacak)” notu düş.
- Talep netleştiğinde:
  - `docs/05-governance/02-stories/` altında **draft** veya gerçek Story aç,
  - Bu tabloya Story ID’yi ve durumu ekle/güncelle.
- Amaç: “Kullanıcı ne istiyor?” sorusunun tek bakışta görülebilmesi ve her talebin en sonunda bir Epic/Story’ye bağlanması.

## 2. Kullanıcı Talepleri Listesi

Request ID, serbest formda `FR-00X` gibi verilebilir; yalnız benzersiz olmasına dikkat edilir.

| Request ID | Kaynak / Kanal        | Kısa Özet                              | Durum            | Epic  | Story ID | Not |
|-----------|------------------------|----------------------------------------|------------------|-------|----------|-----|
| FR-001    | İç ekip (platform)     | Manifest & sözleşme platformu v1.0 – remotelar ve sözlükler için güvenli manifest + SRI + kontrat testleri | Planned        | E04   | E04-S01  | ADR-001, ADR-002, ADR-007 ile hizalı (SPEC-E04-S01-PLATFORM-MANIFEST-V1) |
| FR-002    | İç ekip (ops/güvenlik) | Canary rollout + feature flag yönetişimi + DR/HA guardrail’leri v1.0 | Planned        | E05   | E05-S01  | ADR-006, ADR-009, ADR-010, ADR-013 ile hizalı |
| FR-003    | Ürün / deneyim        | Telemetry & observability korelasyonu v1.0 – TTFA, hata oranı, FE↔BE trace zinciri | Planned        | E06   | E06-S01  | ADR-004, ADR-011 ile hizalı |
| FR-004    | Ürün / deneyim        | Globalization & Accessibility v1.0 – i18n sözlük pipeline’ı + A11y süreç standardı | Planned        | E07   | E07-S01  | ADR-008, ADR-014 ile hizalı |
| FR-005    | İç ekip (design/FE)    | Theme & Shell Layout Figma Kit v1.0 – çok eksenli tema (appearance/density/radius/elevation/motion) + Shell layout + AG Grid görsel kuralları | Planned        | E03   | E03-S01  | `docs/05-governance/06-specs/SPEC-E03-S01-THEME-LAYOUT-V1.md`, `docs/05-governance/05-adr/ADR-016-theme-layout-system.md` ile hizalı (Figma Kit – Shell Layout v1.0) |
| FR-006    | İç ekip (platform/FE/BE) | Kod kalitesi el kitabı (STYLE-BE-001, STYLE-FE-001, STYLE-API-001) yaygınlaştırma | Planned        | QLTY | QLTY-01-S1..S4 | `docs/00-handbook/STYLE-*.md` ile hizalı |
| FR-007    | İç ekip (design/FE)    | Theme Runtime Integration – data-* eksenleri, semantic token → CSS var → Tailwind, AG Grid tema/density ve access varyantlarının kod entegrasyonu | Planned        | E03   | E03-S02  | `docs/05-governance/02-stories/E03-S02-Theme-Runtime-Integration.md` |
| FR-008    | İç ekip (UX/FE)        | Kalıcı sol navigation paneli | New             | E03   | (henüz yok, draft açılacak) | Shell header’dan bağımsız sol panel taslağı hazırlanacak; responsive ve yetki durumları netleştirilecek. |
| FR-009    | İç ekip (UX/FE)        | Koyu görünümde palet/overlay farkı + grid yüzeyi için yeni tema aksı | Planned         | E03   | E03-S03 | Dark mode’da her tema için overlay/aksan token’ı; grid/table yüzeyi için ayrı tema aksı ve CSS var gereksinimi belirlenecek. |
| FR-010    | İç ekip (robotik operasyon) | Robotik görevler ekranı – robot task yönetimi ve izleme paneli | New | (henüz yok) | (henüz yok, draft açılacak) | Modül bazlı servis işleri netleşince Epic/Story açılacak; UI/ops gereksinimleri toplanıyor. |
| FR-011    | İş zekâsı / operasyon | Dashboard builder – kullanıcıların kendi raporlarını hazırlayabileceği standart BI aracı | New | (henüz yok) | (henüz yok, draft açılacak) | Self-service dashboard ekranı; veri kaynakları ve yetki modelini netleştirmek için analiz aşamasında. |
| FR-012    | ERP ekibi | ERP’den gelen kullanıcıların otomatik olarak Keycloak + user-service’e provisioning ve düzenli senkronizasyonu | New | QLTY | (henüz yok, draft açılacak) | ERP → Keycloak entegrasyonu ve `scripts/keycloak/sync-keycloak-users.sh` cron akışı ile user-service tarafını güncel tutma talebi. |
| FR-013    | İç ekip (platform/güvenlik) | Permission-service tabanlı merkezi yetki modeli (mini Zanzibar) – scope’lu izin kataloğu + AuthorizationContext katmanı | Planned | QLTY | QLTY-BE-AUTHZ-SCOPE-01 | Permissions + scope tablolarının permission-service’de tutulması, Keycloak claim’leriyle eşlenmesi ve servislerde AuthorizationContext/cache yapısının standardize edilmesi talebi. |

**Durum değerleri (öneri):**
- `New` – Listeye yeni eklendi, henüz triage edilmedi.
- `Triage` – Değerlendiriliyor, hangi Epic/Story ile çözüleceği netleştiriliyor.
- `Planned` – İlgili Story oluşturuldu ve `docs/05-governance/PROJECT_FLOW.md` altında akışa alındı.
- `Done` – İlgili Story/Acceptance tamamlandı.
- `Rejected` – Bilinçli olarak yapılmaması kararlaştırılan talep.

> Notlar:
> - Ayrıntılı iş değeri, kapsam, kabul kriterleri vb. bilgiler **Story** dokümanında tutulmalıdır (`docs/05-governance/02-stories/*.md`).  
> - Bu tablo yalnızca talepleri **toplama ve takip** amaçlıdır; tek gerçek gereksinim kaynağı Story + Acceptance + Spec zinciridir.
