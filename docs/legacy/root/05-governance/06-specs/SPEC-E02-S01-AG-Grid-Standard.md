# SPEC-E02-S01-AG-Grid-Standard
**Başlık:** AG Grid Standardı & Deneyim Bütçeleri  
**Versiyon:** v1.0  
**Tarih:** 2025-12-04  

**İlgili Dokümanlar:**  
- EPIC: docs/05-governance/01-epics/E02.md  
- ADR: docs/05-governance/05-adr/ADR-005-ag-grid-standard-and-experience-budgets.md  
- ACCEPTANCE: docs/05-governance/07-acceptance/E02-S01-AG-Grid-Standard.acceptance.md  
- STORY: docs/05-governance/02-stories/E02-S01-AG-Grid-Standard.md  
- STYLE GUIDE: docs/00-handbook/NAMING.md, frontend/style guides (STYLE-FE-001), theme specs (SPEC-E03-S01-THEME-LAYOUT-V1, SPEC-E03-S02-THEME-RUNTIME-V1)  

**Etkilenen Modüller / Servisler:**  

| Modül/Servis   | Açıklama / Sorumluluk                                    | İlgili ADR |
|----------------|-----------------------------------------------------------|------------|
| frontend shell | Grid teması, shared layout, global CSS/token entegrasyonu | ADR-005    |
| mfe-users      | AG Grid standardı uygulayan MFE                           | ADR-005    |
| mfe-access     | AG Grid standardı uygulayan MFE                           | ADR-005    |
| mfe-reporting  | AG Grid standardı uygulayan MFE                           | ADR-005    |
| mfe-audit      | AG Grid standardı uygulayan MFE                           | ADR-005    |
| ui-kit         | Grid şablonları (EntityGridTemplate/SSR config)           | ADR-005    |

---

## 1) Amaç ve Kapsam
- Amaç: Tüm MFE’lerde tablo/listeler için AG Grid Enterprise’ı tek ve tutarlı standartla kullanmak; performans ve erişilebilirlik (WCAG-AA) bütçelerini tanımlamak ve CI’da doğrulamak.
- Kapsam: Grid konfigürasyonu, tema/token uyumu, erişilebilirlik (klavye/ARIA), performans/a11y bütçeleri ve CI gate’leri.  
- Kapsam dışı: Gelişmiş raporlama/analitik grid davranışları (E02-S02 altında ele alınır); backend API şemalarının büyük refactor’ları.

## 2) Hedefler (Goals)
1. Legacy grid çözümlerini kaldırıp tüm grid ekranlarını AG Grid Enterprise’a taşımak (Users, Access, Reporting, Audit, Shell).  
2. Varsayılan konfigürasyon ve minimum konfor seti: SSRM (büyük veri) + ClientSide (küçük veri) kuralları; valueFormatter önceliği, cellRenderer yalnızca gerektiğinde.  
3. Tema/token uyumu: renk/surface/typography/spacing/padding/pinned/gridline/hover/selected/edit/error/readonly durumları Light/Dark/HC profillerde AG Grid temasıyla hizalı.  
4. Erişilebilirlik: Klavye navigasyonu (Tab/Shift+Tab, fokus halkası, cell/row selection), ARIA etiketi ve rol seti; axe/Lighthouse ile otomatik doğrulama.  
5. CI kalite kapıları: bundle-size (shell <250 KB gzip, MFE’ler <300 KB ilk rota), Lighthouse a11y skor eşiği, axe smoke, AG Grid ekranlarında temel Playwright smoke (grid açılır, veri yüklenir, klavye hareketi çalışır).

## 3) Tasarım Kararları
- Grid Kütüphanesi: AG Grid Enterprise zorunlu; alternatif grid çözümleri yasak (ADR-005 ile uyumlu).  
- Veri Modeli: SSRM varsayılan; küçük veri sayfalarında ClientSide ancak dokümante edilerek. Filtre/sort/paging sözleşmesi E02-S02 ile uyumlu kalır.  
- Renderer/Formatter: valueFormatter + cellClassRules öncelikli; custom cellRenderer yalnızca veri formatlama/ikon/multi-line ihtiyaçlarında.  
- Tema/Token: AG Grid tema katmanı theme.css/token’lardan beslenir; header/body/selected/hover/row stripes/pinned/grup başlıkları tema var’ları ile boyanır (SPEC-E03-S01/S02 referansı).  
- Erişilebilirlik: Fokus halkası, ARIA role/gridcell/row, tab ordering; AG Grid’in yerel a11y opsiyonları açık, ek custom renderer’lar aria-label/aria-describedby ekler.  
- Performans: Default columnDefs minimal; column resize/autoSize sınırlandırılır; SSRM cache/rowBuffer konservatif; ağır custom renderer’lar yasaklanır.  
- Build/CI: size-limit veya eşdeğerle bundle eşiği; Lighthouse a11y/perf eşiği; axe smoke; Playwright grid smoke (grid açılır, Tab ile fokus hareketi, en az bir veri satırı).  

## 4) Mimari / Akış
- Shell/MFE: Grid sayfası → UI Kit grid şablonu (EntityGridTemplate) → AG Grid (SSRM/ClientSide).  
- Tema: shell theme runtime’dan gelen CSS var’ları AG Grid temasıyla eşlenir (overlay, header/bg, zebra, selection, pinned).  
- SSRM API: UI’dan gelen filter/sort/paging parametreleri backend sözleşmesi (E02-S02) ile uyumlu; pagination + total + items cevabı.  
- CI Akışı:  
  - `tokens:build` → `build` → `size-limit` → `lighthouse` (a11y/perf) → `axe` smoke → Playwright grid smoke.  
  - Threshold ihlali merge’i bloklar.

## 5) Gereksinimler
- Zorunlu konfig: `suppressFieldDotNotation=true`, `rowModelType=serverSide` (büyük ekranlar), `enableCellTextSelection=false`, `animateRows=false` (perf), `rowSelection=single|multiple` dokümante.  
- Erişilebilirlik: `aria-label`/`aria-describedby` bağlamı; fokus halkası theme var ile görünür; klavye ile sıralama/filtre erişimi.  
- Tema: Header/bg/hover/selected renkleri tema var’larından; zebra opsiyonel ama tutarlı; pinned sütunlar belirgin.  
- Performans: İlk render p95 < 2.5s (sıcak), grid bundle katkısı limit altında; ağır renderer yok.  

## 6) Sınırlar / Kapsam Dışı
- İleri düzey raporlama (pivot, charting) ve domain spesifik grid aksiyonları (mass update, rule builder) bu SPEC’te yok; E02-S02’de ele alınır.  
- Backend schema/contract büyük refactor’ları bu story kapsamı dışında.  

## 7) Test Stratejisi
- Unit: Grid config yardımcıları, formatter/renderer yardımcıları.  
- Integration/UI: Playwright grid smoke (Users, Reporting, Access, Audit) – grid açılır, veri yükler, Tab ile fokus hareketi, en az bir hücre seçimi.  
- A11y: axe smoke + Lighthouse a11y skor eşiği.  
- Perf: bundle-size gate (size-limit) + Lighthouse perf skor eşiği.  

## 8) Rollout ve Geri Alma
- Rollout: MFE bazında kontrollü; feature flag gerekirse grid standardı flag’i.  
- Geri alma: Eski grid varyantına geçici dönüş yalnızca acil durumda, takip eden sprintte kaldırılır; flag temizliği zorunlu.  

## 9) Riskler ve Mitigasyon
- Flaky a11y/perf gate → eşiği gerçekçi tut, CI’de stabil olmayan testleri iyileştir, geçici disable yerine iyileştirme planı.  
- Legacy grid kalıntıları → repo taraması + checklist; kalanların takibi için ayrı task.  
- Tema uyumsuzluğu → tema var’ı eşleme rehberi, görsel kontrol (Figma AG Grid Test Panosu).  

## 10) Açık Sorular
- Audit/Reporting gridlerinde pivot/large dataset için ek konfig gerekir mi? (E02-S02’de netleştirilecek.)  
- Klavye kısayolları (özellikle inline edit) standarda dahil edilecek mi? (Varsayılan: hayır, yalnızca navigasyon.)  
