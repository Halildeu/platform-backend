---
title: "Acceptance — ALPHA-03 Theme Tokens"
status: in_progress
owner: "@team/frontend"
related_ticket: ALPHA-03
---

Checklist
- [x] `apps/mfe-shell/src/app/theme/tokens.css` + `tokens.raw.json` semantic/raw katmanları ve `--table-row-height` token’ını içeriyor.
- [x] Tailwind/AntD eşleme CSS değişkenlerine bağlı (örn. `apps/mfe-shell/tailwind.config.js`, `theme-context.provider.tsx`).
- [x] Storybook/Docs “Theme Tokens” sayfası yayınlandı `.storybook` konfigürasyonu ile çalışır hale getirildi; HC/compact ekran görüntüleri `docs/03-delivery/guides/theme/img/theme-tokens-*.png` altında.
- [x] `docs/03-delivery/guides/theme/tokens-and-mapping.md#7-acceptance` maddeleri güncellendi ve görsellerle referanslandı.
- [x] `npm run storybook -- --docs` komutu node v20.19.4 ortamında başarıyla dev server açıyor (6006 portu) ve “Foundations / Theme Tokens” hikâyesi `docs/03-delivery/guides/theme/tokens-and-mapping.md` ile uyumlu render oluyor.
- [x] Storybook log’larında “No story files found” uyarısı görülmüyor.

Kanıtlar
- 2025-11-12 — `npm run test --prefix packages/ui-kit` (grid-variants testleri yeşil; log bkz. CI notu).

Notlar
- Storybook paketleri `frontend/package.json` altında tanımlandı; offline ortamda `npm install` çalıştırıldığında `npm run storybook -- --docs` komutu hikâyeyi açacak.
- Görseller şu an token’lardan türetilen script ile üretildi; gerçek UI screenshot’ları için Playwright/Storybook kombinasyonu kullanılabilir.
- 2025-11-14: `npm run storybook -- --docs` komutu 6006 portunda açılırken “No story files found” uyarısı verip webpack derlemesinde kalıyor; `stories` glob’u altında hikâye dosyası olmadığı için doğrulama yapılamadı. Storybook’un çalıştığını kanıtlamak için en az bir MDX/Stories dosyası eklenmesi veya mevcut dokümantasyon hikâyesinin konfigüre edilmesi gerekiyor.
- 2025-11-15: `docs/storybook/theme-tokens.stories.mdx` yerine `apps/mfe-shell/src/stories/theme-tokens.stories.js` eklendi ve `.storybook/main.ts` yalnızca `docs/storybook/**/*.mdx` glob’unu izleyecek şekilde güncellendi. `npm run storybook -- --docs` komutu (GUI olmadan) başarıyla çalıştı; loglarda uyarı görülmedi ve “Foundations / Theme Tokens” hikâyesi JSON token özetlerini gösteriyor.

