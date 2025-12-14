# SPEC-E03-S02-THEME-RUNTIME-V1
**Başlık:** Theme Runtime Integration v1  
**Versiyon:** v1.0  
**Tarih:** 2025-11-19  

**İlgili Dokümanlar:**  
- ADR: docs/05-governance/05-adr/ADR-016-theme-layout-system.md  
- ACCEPTANCE: docs/05-governance/07-acceptance/E03-S02-Theme-Runtime-Integration.acceptance.md  
- STORY: docs/05-governance/02-stories/E03-S02-Theme-Runtime-Integration.md  
- STYLE GUIDE: docs/00-handbook/NAMING.md  

---

# 1. Amaç (Purpose)
- Çok eksenli tema modelini (appearance/density/radius/elevation/motion) runtime’da uygulanabilir hale getirmek.  
- Semantic token → CSS var → Tailwind eşleşmesiyle raw renk/sınıf kullanımını engellemek.  
- Shell, UI Kit, AG Grid ve access varyantlarını ortak tema sözleşmesiyle yönetmek.

# 2. Kapsam (Scope)

### Kapsam içi
- HTML data-* eksenleri ve switch API (varsayılan profil dahil).  
- Figma token JSON → CSS var → Tailwind mapping, semantic isimler.  
- UI Kit tema/density/radius/elevation/motion opsiyon listeleri.  
- AG Grid header/pinned/row state/zebra/sticky/density semantik renk haritası.  
- Access prop’ları (`access=full|readonly|disabled|hidden`); tooltip/metin davranışı.  
- Visual/a11y regresyon matrisi (appearance × density × radius × elevation × motion).

### Kapsam dışı
- Yeni görsel tema tasarımı (Figma’da kalır).  
- Backend auth/policy değişiklikleri (yalnız UI görünürlük/disable).

# 3. Tanımlar (Definitions)
- Semantic token: `surface.bg`, `text.primary`, `border.subtle`, `state.success.*`, `selection.bg`, `focus.outline`, `accent.info`.  
- Theme eksenleri: appearance (light/dark/high-contrast), density (comfortable/compact), radius (rounded/sharp), elevation (raised/flat), motion (standard/reduced).

# 4. Kullanıcı Senaryoları (User Flows)
- Tema geçişi: kullanıcı seçimi → ThemeController data-* özniteliklerini günceller → UI Kit/AG Grid yeni var’larla boyanır.  
- Compact tablo: yalnız tablo konteynerine density=compact uygulanır; global layout etkilenmez.  
- Yetkisiz işlem: access=disabled + mesaj; güvenlik kritik aksiyonlarda access=hidden.

# 5. Fonksiyonel Gereksinimler (Functional Requirements)
**FR-TR-01:** HTML kökte appearance/density/radius/elevation/motion data-* öznitelikleri set edilebilir, varsayılan profil tanımlı.  
**FR-TR-02:** Figma token export’u CSS var’lara çevrilir; Tailwind semantic isimleri raw renk yerine kullanılır; lint raw renkleri engeller.  
**FR-TR-03:** UI Kit tema/density/radius/elevation/motion opsiyonları kodda listelenir; varsayılan tema IDs kalıcı.  
**FR-TR-04:** AG Grid header/pinned/row state/zebra/sticky/density semantik renk ve state haritaları uygulanır; compact/comfortable satır yükseklikleri sabitlenir.  
**FR-TR-05:** Access prop’ları (`full|readonly|disabled|hidden`) tüm ilgili bileşenlerde desteklenir; disabled durumda tutarlı mesaj/tooltip gösterilir.  
**FR-TR-06:** Visual/a11y regresyon matrisi (appearance × density × radius × elevation × motion) temel ekranlarda yeşildir.

# 6. İş Kuralları (Business Rules)
**BR-TR-01:** Tema değişimi yalnız boyama yapar; density değişimi yalnız hedef konteynerde reflow yaratır.  
**BR-TR-02:** Güvenlik kritik eylemler access=hidden, beklenen ama izinsiz eylemler access=disabled + açıklama.  
**BR-TR-03:** HC modunda metin/ikon AAA, focus ring her temada görünür.  
**BR-TR-04:** Raw renk/hex kullanımına izin verilmez; semantic token zorunlu.

# 7. Veri Modeli (Data Model)
Bu spesifikasyon UI tema runtime’ı içindir; yeni DB şeması veya DTO tanımı yoktur. Gerekirse ileride tema profil kalıcılığı için ayrı bir veri modeli eklenecektir.

# 8. API Tanımı (API Spec)
Yeni backend API tanımı bu sürümde yoktur; tema state’i client-side yönetilir. Persistence gerekiyorsa ek bir API ayrı spekle ele alınacaktır.

# 9. Validasyon Kuralları (Validation Rules)
- Tema/density/radius/elevation/motion değerleri desteklenen enum setiyle sınırlıdır; geçersiz değerler reddedilir ve varsayılan profile düşülür.  
- Lint/guardrail: raw renk/hex sınıfı kullanımı engellenir; semantic token zorunlu kılınır.

# 10. Hata Kodları (Error Codes)
UI-runtime düzeyinde yeni hata kodu yok; geçersiz tema değeri durumunda fallback uygulanır ve konsol uyarısı verilir (kod seviyesinde).

# 11. Non-Fonksiyonel Gereksinimler (NFR)
- A11y: HC modda AAA; focus ring her temada net.  
- Performans: Tema switch yalnız repaint; density reflow lokal.  
- Lint/Guard: raw renk/hex yasaklayan kural; visual/a11y testleri (Chromatic/Playwright + axe) CI’da çalışır.  
- UX/i18n: Tema/density değişimi yön/locale’den bağımsız; string’ler mevcut i18n altyapısını kullanır (kod eklenmez).

- Uygulama notları (özet): HTML data-appearance/density/radius/elevation/motion varsayılanları; Figma token JSON → CSS var → Tailwind mapping; UI Kit `theme-light/dark/hc/compact` opsiyonları; AG Grid header/pinned/row state/zebra/sticky/density semantik renkleri; access prop’ları; appearance × density × radius × elevation × motion test matrisi.

# 12. İzlenebilirlik (Traceability)
- ADR: docs/05-governance/05-adr/ADR-016-theme-layout-system.md  
- Story: docs/05-governance/02-stories/E03-S02-Theme-Runtime-Integration.md  
- Acceptance: docs/05-governance/07-acceptance/E03-S02-Theme-Runtime-Integration.acceptance.md  
- Style guide: docs/00-handbook/NAMING.md  
