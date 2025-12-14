---
title: "Theme Screenshot Kütüphanesi"
status: ready
owner: "FE/Design"
---

Bu klasör, `Foundations / Theme Tokens` hikâyesine ait örnek görüntüleri barındırır. Görseller, token renkleri/density değerleri kullanılarak script ile üretildi (`python docs/.../generate_theme_previews.py`). Şu anda mevcut dosyalar:

- `theme-tokens-light-comfortable.png`
- `theme-tokens-dark-comfortable.png`
- `theme-tokens-high-contrast-comfortable.png`
- `theme-tokens-light-compact.png`

Storybook açıldığında (bkz. `.storybook/main.ts`) bu kombinasyonlar gerçek UI üzerinden tekrar alınabilir. Otomasyon için örnek komut:

```bash
npm run storybook -- --docs &
npx playwright screenshot http://localhost:6006/?path=/docs/foundations-theme-tokens--docs docs/03-delivery/guides/theme/img/theme-tokens-light-comfortable.png
```

Güncellemeler sonrasında `docs/03-delivery/guides/theme/tokens-and-mapping.md#storybookdocs-theme-tokens` ve `shell-reporting-slots.md` dosyalarında ilgili resimlere referans verildi.
