---
title: "Acceptance — E03-S01 Theme Layout System"
status: done
related_story: E03-S01
---

Story ID: E03-S01-Theme-Layout-System

Checklist
- [x] Tema/mode/density vb. eksenler HTML kökünde veri öznitelikleri ile yönetiliyor (mfe-shell ThemeProvider `document.documentElement` üzerinde `data-theme` / `data-mode` / `data-density` özniteliklerini güncelliyor).
- [x] Tailwind config yalnız `var(--token)` bazlı semantic renk referansları içeriyor; ham hex/Ant token referansı yok (semantic renkler `theme.css` ve `tailwind.config.js` ile var tabakasına bağlandı).
- [x] UI Kit primitives ve Shell layout bileşenleri yeni tema modelini kullanacak şekilde güncellendi (en az Shell + grid toolbar/overlay).
- [x] Ant Design bağımlılıkları için ilk temizlik dalgası yapıldı; kalan referanslar kontrol listesiyle takip ediliyor (UI Kit legacy Ant dosyaları kaldırıldı, grid toolbar/overlay var tabakasına bağlandı, `antd/dist/reset.css` shell’den çıkarılıp hafif global reset eklendi).
- [x] Figma Kit – Shell Layout (v1.0) Figma dosyasında sayfa hiyerarşisi (00_Overview, 01_Tokens, 02_Foundations, 03_Components, 04_Patterns, 05_Layouts, 06_Docs, 07_Assets) kurulmuş ve her sayfa için temel içerik (token şeması, component katalogu, pattern ve layout örnekleri, dokümantasyon) doldurulmuş durumda.
- [x] Renk Sistemi & Kontrast Rehberi Figma’da ve dokümantasyonda semantik renk haritası (surface/text/icon/border/interactive/state/selection/focus/disabled/muted) ve Light/Dark/High-Contrast için hedef kontrast oranları ile birlikte tanımlanmış; placeholder, disabled, tooltip, breadcrumb, pinned sütun ve focus halkası gibi bilinen riskli noktalar için checklist oluşturulmuş.
- [x] AG Grid için Figma “AG Grid Test Panosu” oluşturulmuş; başlık, pinned sütun, grup başlığı, satır durumları (normal/hover/selected/edit/error/readonly), zebra, gridline, sticky, boş/hata/yükleniyor ekranları ve en az 20+ hücre tipi (metin, sayı, negatif, link, chip, ikon‑buton vb.) dört resmi tema seti (`shell-light`, `shell-dark`, `shell-hc`, `shell-compact`) altında görsel olarak doğrulanmış.
- [x] Tema geçişi ve kontrast için ölçüm planı (appearance × density × radius × elevation × motion kombinasyonları) Figma tarafında dokümante edilmiş ve QA-02 Visual & A11y acceptance (`docs/05-governance/07-acceptance/QA-02.md`) ile hizalı; High-Contrast modda kritik ekranlar için AAA kontrast ve focus görünürlüğü manuel/araç destekli kontrollerle onaylanmış.
- [ ] SSOT doğrulaması: `frontend/design-tokens/figma.tokens.json` dışında tema/palette/overlay sabiti bulunmaz; generator yalnız bu dosyayı okur.
- [ ] Data-attr sözleşmesi: `data-theme`, `data-density`, `data-radius`, `data-elevation`, `data-motion`, `data-table-surface-tone`, `data-overlay-intensity`, `data-overlay-opacity` root’ta doğru değerleri alır; Shell runtime bu attr’ları tek yazan kaynaktır.
- [ ] Görünüm/radius/table tone röfleksi: `data-theme` light → dark geçişinde panel/bg/kontrast değişir; `data-radius` rounded → sharp geçişinde kontrol kenarları köşelenir; `data-table-surface-tone` soft/normal/strong değişiminde grid arka planı gözle görülür farklıdır.
- [ ] Overlay kontrolleri: UI slider hareketi `--overlay-intensity` ve `--overlay-opacity` CSS var’larını token min/max/default’larına göre clamp ederek değiştirir; modal/overlay örneklerinde görsel değişim doğrulanır.
- [ ] Palette/accent: `data-accent` değişiminde accent/focus renkleri palette token’larına göre güncellenir (örn. violet → aksiyon vurgu ve focus ring rengi değişir).
