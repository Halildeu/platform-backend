---
title: "Mimari Yönetim Kılavuzu"
status: in-progress
owner: "@team/platform-arch"
last_review: 2025-11-03
tags: ["architecture", "process"]
---

# Mimari Yönetim Kılavuzu

Bu doküman, platform içerisindeki mimari çalışmaların nasıl planlanacağı, değerlendirilip onaylanacağı ve hangi çıktının nerede dokümante edileceğini standartlaştırır. Amacımız ekipler arasında aynı dili konuşmak, kritik kararları izlenebilir kılmak ve mimari evrimi doc-as-code modeliyle sürdürebilmektir.

## 1. Katmanlı Mimari Dokümantasyon Haritası

| Katman | Tanım | Zorunlu İçerik | Lokasyon | Sahiplik & Periyot |
| ------ | ---- | -------------- | -------- | ------------------ |
| **Platform** | Tüm servis ve MFE’leri kapsayan üst seviye mimari | Sistem bağlamı, ana akışlar, entegrasyonlar | `docs/01-architecture/01-system/01-backend-architecture.md` (backend) + `docs/01-architecture/01-system/02-frontend-architecture.md` (frontend) | Platform mimari ekibi · 6 ayda bir gözden geçirme |
| **Alan (Backend/Frontend)** | Backend ve frontend ekosisteminin domain bazlı kurgusu | Domain sınırları, teknoloji yığını, servis/MFE haritası | `docs/01-architecture/01-system/01-backend-architecture.md`, `docs/01-architecture/01-system/02-frontend-architecture.md` (frontend repo detayları) | İlgili alan sahipleri · 3 ayda bir |
| **Servis / MFE** | Tekil servis veya mikro-frontend detayları | Sorumluluklar, kritik bağımlılıklar, SLA, runbook linkleri | Servis README’leri. Frontend için: `frontend/docs/01-architecture/*` | Takımın kendisi · Sprint sonunda güncelle |
| **Karar Kaydı (ADR)** | Önemli mimari kararlar ve gerekçeleri | Problem, alternatifler, seçilen çözüm, etkiler | `docs/05-governance/05-adr/ADR-XXX-*.md` | Kararı alan takım · Karar sonrası 48 saat |
| **Çalıştırma / Runbook** | Operasyonel prosedürler, hata senaryoları | Uçtan uca adımlar, rollback, iletişim kanalları | `docs/04-operations/**` | SRE / ilgili ürün ekibi · Çeyreklik doğrulama |
| **Araştırma / RFC** | Henüz onaylanmamış keşif ve araştırmalar | Varsayımlar, açık sorular, PoC sonuçları | `docs/03-delivery/templates/01-rfc/feature-spec-template.md` üzerinden `docs/09-rfc/` | İçerik sahibi · Geçici (status: draft) |

> Not: Her doküman front matter içinde `status`, `owner`, `last_review` alanlarını içermelidir. Review tarihleri doc hygiene toplantılarında kontrol edilir.

## 2. Mimari Çalışma Yaşam Döngüsü

1. **Fikir & Tetikleyici:** Yeni servis/MFE ihtiyacı, büyük refactor, teknoloji değişimi veya risk tespiti.
2. **Hızlı Triyaj:** Platform-arch haftalık seremonisinde fikir backlog’a alınır; kapsam ve etki değerlendirilir.
3. **RFC / Keşif:** Etki alanı büyükse RFC taslağı hazırlanır (template → `docs/03-delivery/templates/01-rfc/feature-spec-template.md`). Bu aşamada varsayımlar, alternatifler ve prototip sonuçları dokümante edilir.
4. **ADR Kararı:** Karar netleştiğinde ADR oluşturulur. ADR sadece onaylı kararları içerir; reddedilen alternatifler mutlaka kaydedilir.
5. **İcra & Dokümantasyon:** Uygulama sırasında ilgili mimari doküman (ör. backend-architecture) güncellenir, servis README’leri ve runbook’lar revize edilir.
6. **Kapanış & Gözden Geçirme:** Release sonrası doc hygiene checklist’i çalıştırılır; ADR referansları roadmap ve runbook’lara linklenir.

## 3. Belgeleme Tetikleyicileri

| Olay | Geleneksel Çıktı | Not |
| ---- | ---------------- | --- |
| Yeni servis veya MFE oluşturma | Servis README + mimari doküman güncellemesi + ADR | Servis isimlendirme, bağımlılıklar, SLA belirtilir |
| Mevcut servis/MFE’de “breaking” değişiklik | ADR (gerekirse), mimari doküman güncellemesi | “Breaking” ölçütleri: API değişimi, data model migration, SLA etkisi |
| Altyapı veya teknoloji yığını değişimi | ADR + system-architecture.md güncellemesi | JVM versiyonu, gateway stratejisi vb. |
| Güvenlik/gizlilik gereksinimi | ADR veya runbook (incident/guardrail) | Örn. Vault stratejisi, OPA policy |
| Operasyonel süreçlerde yeni prosedür | Runbook veya operations rehberi | On-call, incident response, release planı |
| Kullanıcı deneyimi üzerinde köklü değişiklik | UX dokümanı (frontend repo: `frontend/docs/architecture/frontend/ux/`) + ADR linki | Persona, akış, UI kararları |
| Risk/varsayım değişimi | RFC güncellemesi veya kapanışı | Henüz karar alınmadıysa status: draft/review kalır |

## 4. Dosya ve İsimlendirme Kuralları

- **ADR dosya adı:** `ADR-<sıra-no>-<kısa-konsept>.md` (örn. `ADR-012-i18n-pipeline.md`).
- **RFC/Spek klasörü:** `docs/09-rfc/<yyyy>/<rfc-adı>.md`.
- **Servis odaklı ek dosyalar:** `docs/01-architecture/03-services/<servis-adı>.md`. Frontend dokümanları frontend repo altında tutulur: `frontend/docs/01-architecture/<module>/`.
- **Şablon kullanımı:** Yeni doküman açarken `docs/03-delivery/templates/` altındaki uygun template’i kopyalayın (örn. runbook, feature spec, glossary, ADR, metrics).
- **Dil & Format:** Türkçe ana dil; gerektiğinde İngilizce terimler italik. UTF-8, LF, 100 karakter satır limiti önerisi.

## 5. Review ve Onay Süreçleri

- **Doc Hygiene Toplantısı:** Düzenli aralıklarla platform-arch + alan temsilcileri; yeni roadmap yapısına göre `docs/05-governance/PROJECT_FLOW.md` + ilgili `01-epics/` ve `02-stories/` üzerinden geçilir (eski notlar için legacy backlog: `docs/05-governance/roadmap-legacy/backlog.md`).
- **CODEOWNERS:** `docs/01-architecture/**` değişiklikleri @team/platform-arch onayına tabidir. Servise özel dokümanlarda ilgili takım ek reviewer ekler.
- **ADR Onayı:** Kararı alan takım lead’i + platform-arch lead’i “approved” etiketi olmadan merge edemez.
- **Runbook & Operasyon:** SRE ekibinin onayı zorunlu; release öncesi checklist’de kontrol edilir.

## 6. İletişim ve Yayılım

- Önemli mimari kararlar Slack `#architecture-updates` kanalında özetlenir; linkler doküman, ADR ve roadmap’e yerleştirilir.
- Backstage kayıtları güncellenir: Servis/MFE sayfası -> `Architecture & Docs` bölümüne yeni bağlantılar eklenir.
- Release notlarında mimari etkiler (örn. yeni servis eklenmesi) ayrıca belirtilir.

## 7. Sürekli İyileştirme

- **Göstergeler:** Açılan/kapanan ADR sayısı, stale doküman raporu (`scripts/doc-stale-report.ts` planlı), doc PR sayısı.
- **Denetim:** 6 ayda bir kategori yapısı gözden geçirilir; yeni ihtiyaçlar için alt klasörler (örn. `architecture/security/`) açılır.
- **Geribildirim:** Retrolarda “doc ops” maddesiyle deneyimler toplanır; süreç gerekirse ADR ile güncellenir.

---

> Bu kılavuz pratikte işlemediği noktada güncellenmelidir. Süreç içinde sapma tespit ederseniz yeni yapıya uygun bir Story açın (`docs/05-governance/02-stories/*.md`) veya geçiş sürecinde legacy backlog (`docs/05-governance/roadmap-legacy/backlog.md`) içine kayıt açıp bir sonraki doc hygiene oturumuna taşıyın.
