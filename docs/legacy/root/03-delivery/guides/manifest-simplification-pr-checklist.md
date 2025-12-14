---
title: "Manifest Sadeleştirme — PR Checklist (mfe-users / mfe-access)"
status: draft
owner: "@team/frontend"
last_review: 2025-11-12
---

Amaç
- Remote manifestleri minimal, okunur ve standart akışlarla uyumlu hale getirmek; audit CTA ve notify akışını sağlamlaştırmak.

Checklist
- [ ] Shared bağımlılıklar: `react`, `react-dom`, `react-router(-dom)` shell tarafından single/singleton; manifestte tekrar yok.
- [ ] Route tanımları yalnız remote içinde; shell’de sadece alias/guard yönlendirmeleri var.
- [ ] i18n anahtarları (title/description/breadcrumb) ham metin değil; manifestte key kullanılıyor.
- [ ] Audit CTA: Mutasyonlarda `auditId` dönüyor; FE `notifyAuditId(auditId)` çağırıyor. Link `/admin/audit/events?auditId=<id>`.
- [ ] Telemetry: Kritik eylemlerde shell telemetry emit var (örn. `users.export_csv.stream.*`).
- [ ] Build boyutu: gereksiz kütüphane veya duplike paylaşımlar yok; MF share konfigleri temiz.
- [ ] Dark/Light uyumu: UI Kit tema değişkenleri kullanılıyor; raw renk yok.
- [ ] E2E: ilgili rota/flow için en az 1 smoke senaryo var; CI yeşil.
- [ ] Belgeleme: değişiklikler `docs/03-delivery/guides/manifest-simplification-audit-cta.md` referanslı.

Notlar
- Paylaşım/pinning değişiklikleri sonrası shell ve remote dev server’ları birlikte ayağa kaldırıp smoke test yapın.

