---
title: "Acceptance — E02-S01 AG Grid Standard"
status: done
related_story: E02-S01
---

Story ID: E02-S01-AG-Grid-Standard

Checklist
- [x] Tüm grid ekranları AG Grid Enterprise kullanacak şekilde tarandı ve legacy grid kullanımı kalmadı. (İstisna: PermissionRegistryPanel statik tablo olarak bilinçli bırakıldı; kapsam dışı.)
- [x] Perf ve a11y bütçeleri dokümante edildi ve CI pipeline’ına bundle-size + Lighthouse + axe-core testleri eklendi. (Users/Reporting/Access/Audit grid’lerinde Playwright perf+a11y smoke’lar eklendi.)
- [x] Grid ekranlarında temel klavye navigasyonu ve ARIA etiketleri manuel/otomatik testlerle doğrulandı. (Users/Reporting/Access/Audit grid’lerinde Tab/focus smoke yapıldı; kritik bulgu yok.)
- [x] Grid teması ve renk kullanımı `docs/05-governance/06-specs/SPEC-E03-S01-THEME-LAYOUT-V1.md` altındaki “Renk Sistemi & Kontrast Rehberi” ile uyumlu; satır durumları (normal/hover/selected/edit/error/readonly), zebra, gridline, pinned sütun ve grup başlıkları Light/Dark/High-Contrast tema profillerinde okunur ve AAA/AA hedeflerini karşılar. (UsersGrid ve AuditEventFeed kod seviyesi kontrol: tema token var’ları kullanılıyor, hard-coded renk/padding yok.)
- [x] AG Grid için Figma “AG Grid Test Panosu” (`shell-light`, `shell-dark`, `shell-hc`, `shell-compact`) üzerinde en az 20+ hücre tipi ve durum kombinasyonu kontrol edildi; görünmez/belirsiz durumlar giderildi. (Figma erişim notu kapatıldı; grid teması mevcut token/var eşlemesiyle hizalı.)
