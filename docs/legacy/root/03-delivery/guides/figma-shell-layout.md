---
title: "Figma Kit – Shell Layout (v1.0)"
status: published
owner: "@team/design-system"
last_review: 2025-11-04
---

# Figma Kit – Shell Layout (v1.0)

> React + Ant Design + Tailwind tabanlı ERP kabuğu için Figma Kit iskeleti. Tasarım ↔ kod senkronu; token mimarisi; component/pattern/layout hiyerarşisi; erişilebilirlik, tema ve yayınlama kuralları.

## 0) Sayfa Hiyerarşisi (Figma Pages)
1. **00_Overview** – Kit amaçları, kullanım rehberi, sürüm notları, changelog, “Nasıl Kullanılır” gif + kısa notlar.
2. **01_Tokens** – Renk, tipografi, spacing, radius, shadow, z-elevation, border, motion, breakpoints. Figma Tokens/Styles + export yönergesi.
3. **02_Foundations** – Grid (12/24 kolon, 8pt), container presetleri, light/dark eşleşmeleri, kontrast örnekleri.
4. **03_Components** – Button, Input, Select, Tabs, Tag, Tooltip, Dropdown, Avatar, Badge, Breadcrumb, Card, Modal, Drawer, Toast, Pagination + Shell özel (Top Bar, Sidebar, Page Header, Filter Bar, Data Table).
5. **04_Patterns** – Arama + Filtre şeridi, Toolbar, Empty State, Onay akışı, Form düzenleri, Bildirim + Quick Actions.
6. **05_Layouts** – Shell Layout (Top Bar + Sidebar + Content), Dashboard, List → Detail, Wizard, Settings; mobil/kompakt varyantlar.
7. **06_Docs** – İsimlendirme, variant kuralları, WCAG 2.2, motion, Figma ↔ Tailwind ↔ AntD tabloları.
8. **07_Assets** – Logo setleri, ikon kütüphanesi (24px grid), illüstrasyonlar, dosya tipleri ve lisans notları.

## 1) Token Sheet (01_Tokens)
- Kural: Bileşenler token dışında renk/değer kullanmaz.
- Renk token tablosu (brand/primary, neutral, accent, semantic) Tailwind ve Ant Design map’iyle birlikte.
- Spacing (space/1..8), radius, shadow, elevation, typography, motion, breakpoints tanımlı.

## 2) Foundations
- Grid: 12 kolon (desktop), 24 kolon (yoğun). Gutter space/4.
- Container preset 1280/1440/1920.
- Kontrast: AA ≥ 4.5:1, AAA ≥ 7:1.
- Hit area ≥ 44×44px, RTL swap kuralları.

## 3) Component Kataloğu
- İsimlendirme formatı: Category/ComponentName.
- Variant props: size/state/theme/density/elevation/tone.
- Shell Top Bar, Sidebar, Page Header, Filter Bar, Data Table, Form Controls.

## 4) Patterns
- Toolbar, Empty State, Modal/Onay, Form düzenleri, Bildirim + Quick Actions.

## 5) Layouts
- Shell Layout yapısı ve kısıtları (Sidebar 256/72px, Content max 1440, sticky Top Bar & Filter Bar).

## 6) Erişilebilirlik & Motion
- AA/AAA, klavye navigasyonu, focus ring, motion süreleri, reduce motion desteği, RTL notları.

## 7) Figma ↔ Tailwind ↔ Ant Design Tablosu
- Birincil renk, yüzey, kart, sınır, başlık, yardım metni, focus eşlemeleri.

## 8) İsimlendirme & Variant Kuralları
- Component isimleri, variant property adları, autolayout + space/*, renk = token, 24px ikon grid, sidebar hiyerarşi sınırı.

## 9) Yayınlama & Versiyonlama
- Library publish, SemVer, 00_Overview changelog, tokens export → repo → Storybook guard.

## 10) QA Checklist
- Token tabanlı kullanım, light/dark eşleşmesi, focus ring, kontrast testleri, variant isimleri, component docs, token export testi.

## 11) Plugin & Notlar
- Figma Tokens/Style Dictionary, Figma Lint, A11y Color Contrast, Content Reel.

## 12) Tema Mimarisi (Tailwind Çoklu Tema)
- Eksenler: Appearance, Density, Radius, Elevation, Motion (data-attr ile).
- Token hiyerarşisi: raw vs semantic; component’ler semantic token tüketir.
- Figma variables & modes (light/dark/high-contrast, comfortable/compact, rounded/sharp, raised/flat, standard/reduced).
- CSS variable örnekleri + Tailwind config extend (colors/spacing/radius/shadow/typography). class="bg-bg text-text shadow-sm" gibi semantik sınıflar.
- Component tema slotları (Top Bar, Sidebar, Page Header, Filter Bar, Data Table).
- Örnek tema setleri: Serban Light/Dark/High-Contrast/Compact.
- QA/test planı (Chromatic/Playwright matrisi, axe-core, density hizaları, tema switch performansı).
- Migrasyon adımları: semantic layer, AntD adaptörü, semantic class’lara geçiş, data-attr, Data Table compact varyant.

## 13) Tema Setleri – Figma Yönergesi
- Her tema için doğrulama frame’i (Top Bar + Sidebar + Data Table), kontras/focus/density kontrolleri.
- Variables & Styles semantik isimler; component katmanları raw renk kullanmaz.

## 14) Prototip & Onay Akışı
- Variant paneli property’ler, appearance × density matrisi, high-contrast testleri, kabul kriterleri (semantic stil haritası eksiksiz, layout bozulmuyor, density lokal, boş/hata UI’ları net).

## 15) Performans Kontrol Listesi
- Repaint/Reflow/Composite matrisi, tema anahtarı kökte, density lokal, tipografi stabil, gölge minimal, virtualization zihniyeti, anti-pattern listesi.

## 16) Tema Geçişi Ölçüm Planı
- Senaryolar (Light↔Dark, High-Contrast, Density, Radius/Elevation, Motion), ölçüm yöntemi (akıcılık, reflow kapsamı, kontrast, hizalar), rapor şablonu, Go/No-Go kriterleri.

## 17) Bakım & Sürümleme
- 00_Overview changelog, semantic stil sözleşmesi donmuş, tema değişiklikleri küçük paketler halinde, rollback planlı.

> Sonuç: Bu iskelet, tasarımcı ve geliştiricinin aynı token/tema sözlüğüyle çalışmasını sağlar; değişiklikler güvenle yayımlanır.
