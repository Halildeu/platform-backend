# E03-S01-S01 — Accent Primary & Focus Var Yayma

**Durum:** In Progress  
**Epic:** E03 – Theme & Layout System  
**İlişkili SPEC:** docs/05-governance/06-specs/SPEC-E03-S01-THEME-LAYOUT-V1.md  
**İlişkili ADR:** ADR-019-theme-ssot.md, ADR-016-theme-layout-system.md  
**Bağlı Acceptance:** docs/05-governance/07-acceptance/E03-S01-Theme-Layout-System.acceptance.md  
**Tarih:** 2025-12-06  

## Amaç
Primary/CTA/link/focus stillerini hard-coded renklerden çıkarıp `--accent-*` token var’larına bağlamak. Tema preset’i (appearance + accent) değiştiğinde bu alanlar otomatik renk değiştirip HC modda yüksek kontrast sağlayacak.

## Kapsam
- UI Kit: Primary Button komponenti, primary/CTA link stili, focus ring/outline (klavye navigasyonu).  
- Shell + en az 1 MFE (users veya audit): üst menü/toolbar’daki primary action butonu.  
- Token: `figma.tokens.json` accent altında primary/hover/focus/soft alanları zorunlu.

## Acceptance
1) Preset geçişi (Açık→Mor→Zümrüt→Gün Batımı→Okyanus→Grafit→HC) sırasında primary/CTA renkleri accent’e göre değişir; `<html>` `data-accent` uyumlu.  
2) HC modunda primary buton + focus ring yüksek kontrast (token’dan gelen değerlerle) gözle görülür.  
3) Kod incelemesinde primary/action/focus stillerinde hex/rgba/opacity sabiti kalmamış, `var(--accent-*)` kullanılıyor.  
4) `theme.css` içinde her `data-accent="…"` için `--accent-primary`, `--accent-primary-hover`, `--accent-focus`, `--accent-soft` var’ları üretilmiş.  
5) En az 1 MFE’de (users veya audit) grid/toolbar primary action butonu preset değişiminde renk değiştiriyor (accent’e bağlı).

## Durum Notu (2025-12-06)
- Teknik wiring tamamlandı; build yeşil.  
- UX gözlemi: accent etkisi buton/toolbar’da zayıf, HC kontrastı artırılmalı.  
- Overlay/density/radius/elevation eksenleri UI’da hâlâ görsel olarak uygulanmıyor.  
- Takip: E03-S01-S01.1 (Accent/HC tuning) ve eksen/overlay uygulaması için yeni dilimler açılacak.

## Notlar
- HC için accent değerleri yüksek kontrastlı olmalı.  
- Hover/focus token’ları eksikse `figma.tokens.json`’a eklenip generator’a dahil edilecek.  
