---
title: "Acceptance — E03-S05 Theme Personalization"
status: ready
related_story: E03-S05
---

Story ID: E03-S05-Theme-Personalization

 Checklist
- [x] Global temalar yalnız THEME_ADMIN tarafından düzenlenir/publish edilir; kullanıcılar sadece seçer veya “kopyala ve özelleştir” yapar.  
- [x] Kullanıcı başına en fazla 3 kişisel tema; limit UI’da görünür (n/3) ve API tarafında bloklanır (409/422).  
- [x] Theme registry tanımlı; picker UI sadece registry’deki alanları ve editableBy kuralını uygular (USER_ALLOWED vs ADMIN_ONLY).  
- [x] Picker değişiklikleri semantic CSS var’larına yazılır; figma.tokens.json değişmez; ham renk kullanımı lint ile engellenir.  
- [x] Non-editable token listesi (action.danger.*, status.danger|warning|success) user override’ına kapalı; admin için izinli.  
- [x] Tema kartları mini önizleme gösterir ve şu 5 öğeyi içerir: layout bg, panel bg, text-primary/secondary, accent buton şeridi, overlay şeridi; seçili tema net highlight edilir.  
- [x] Persist: tema seçimi profil + localStorage’da saklanır; sayfa yenilendiğinde aynı tema uygulanır.  
- [x] Resolved tema servisi (örn. /me/theme/resolved) global + user override’ı tek JSON’da döner; FE bunu uygular. Çıktı en azından themeId, type(GLOBAL|USER), appearance, surfaceTone, axes, tokens haritasını içerir.  
- [x] ERP action/danger renkleri user override’ına kapalı; admin düzenlediğinde kontrast/uygunluk uyarısı gösterilir.  
- [x] Smoke: global tema seçimi, user tema create/edit/delete (limit dahil), picker override (surface/text/border/accent/overlay), AppLauncher/popover/modal/drawer renk güncellemeleri.

Notlar
- HC ve dark modda kontrast uyarısı en az AA eşiğini kontrol eder.  
- Lint/guard: `lint:semantic` ve raw renk yasakları pipeline’da bloklayıcı kalır; komponentlerde ham renk kullanılmaz.  
- Tema switch smoke: shell, modal/drawer, popover/menu, grid + panel renkleri seçilen temaya tepki verir.
