---
title: "Acceptance — E01-S05 Login"
status: done
related_story: E01-S05
---

Story ID: E01-S05-Login

Checklist
- [x] Login, Register, Unauthorized ve LoginPopover ekranlarında Ant Design bileşenlerine ait import veya kullanım kalmamıştır.
- [x] Tüm auth metinleri `@mfe/i18n-dicts` `common` namespace’i altında tanımlıdır; Shell kodunda hard-coded TR metin kalmamıştır (login TR fallback’leri temizlendi, `auth.login.*` key’leri kullanılıyor).
- [x] Dil seçici TR/EN/DE/ES arasında geçiş yaptığında header + login + register + unauthorized + login popover metinleri seçilen dilde görüntülenir.
- [x] Pseudo locale build (i18n-smoke) login/register/unauthorized ekranlarında bozulma olmadan çalışır.  
  - Kanıt: `npm run i18n:pseudo` (frontend) başarıyla çalıştı; pseudo/common güncellendi.
- [x] Temel a11y kontrolleri (klavye navigasyonu, focus ring görünürlüğü, kontrast) auth ekranları için geçmiştir.
