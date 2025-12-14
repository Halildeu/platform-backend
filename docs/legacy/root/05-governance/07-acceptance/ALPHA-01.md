---
title: "Acceptance — ALPHA-01 Proje Charter"
status: pending
owner: "PM/TechLead"
related_ticket: ALPHA-01
---

Checklist
- [x] Proje kapsamı ve hedefler yazıldı
- [x] KPI/ölçütler tanımlı
- [x] Milestone planlandı
- [x] Riskler ve varsayımlar yazıldı

Proje Kapsamı & Hedefler
- Shell + Reporting (ve tüm yeni MFE’ler) için tekil tema token sözlüğü (`appearance`, `density`, `radius`, `elevation`, `motion`) kullanmak.
- Theme token’larını Storybook “Foundations / Theme Tokens” hikâyesi ve `docs/03-delivery/guides/theme/tokens-and-mapping.md` dokümanı üzerinden yayımlamak.
- AG Grid, Ant Design, Tailwind ve shell komponentlerinin tamamını bu token’larla hizalamak; harici hex kullanımlarını lint/review ile engellemek.
- A11y/WCAG AA kontrast, focus ve motion gereksinimlerini sağlayacak referans tema setleri (light, violet, emerald, sunset, ocean + high-contrast) sunmak.

KPI / Ölçütler
- Storybook “Foundations / Theme Tokens” hikâyesi yayında: %100 (var/yok).
- Token kaynağı dışındaki hex kullanımı: 0 (lint/review).
- Yeni MFE ekranlarında token uyum oranı: 100% (release checklist).
- A11y testleri: kritik/severe ihlal sayısı = 0 (Playwright raporları).

Milestone
1. Sprint N: Token sözlüğü + Storybook hikâyesi (tamamlandı).
2. Sprint N+1: Shell + Reporting + UI Kit bileşenlerinin token’larla hizalanması, AG Grid theming.
3. Sprint N+2: Diğer MFE’lere yayılım, kontrast/a11y doğrulamaları, dokümantasyon ve screenshot güncellemesi.

Riskler & Varsayımlar
- Varsayım: Tüm MFE’ler token dosyasını read-only tüketir; manual override yapılmaz.
- Risk: Yeni modüller token seti dışı renk kullanırsa a11y regresyonu oluşur → Mitigasyon: code review + lint.
- Risk: Storybook hikâyesi güncel tutulmaz → Mitigasyon: her tema güncellemesinde Storybook dev server ile kontrol, dokümana ekran görüntüsü.
- Risk: Token değişikliği UI kit ile MFE’ler arasında senkron kaybına yol açabilir → Mitigasyon: versioning ve release check.

