---
title: "Legacy: Ant Design → Tailwind / Semantic Token Eşlemeleri"
owner: "@team/platform-fe"
last_review: 2025-11-15
status: migration_in_progress
---

> Bu belge **geçiş süreci** içindir. Ant Design temalarını Tailwind + semantic token modeline taşırken kullanılacak tek rehberdir. Uygulama kodu tamamen Tailwind primitives’e taşındığında bu belge arşivlenecektir.

## Güncel Durum (2025-11-15)

- `packages/ui-kit/src/layout/PageLayout/PageLayout.tsx` ve `FilterBar/FilterBar.tsx` Tailwind tabanlı hale getirildi, Ant Design bağımlılığı kaldırıldı.
- ReportFilterPanel ve FormDrawer tamamen Tailwind primitives ile yeniden yazıldı; Reporting MFE filtre modülleri yeni API’ye göre güncellendi.
- DetailDrawer Tailwind’e taşındı; Users/Audit MFE’ler yeni çekmeceyi kullanacak şekilde güncellendi (Ant Drawer bağımlılığı temizlendi).
- EntityGridTemplate toolbar’ı (fullscreen, tema, quick filter, variant seçici, export butonları) Ant komponentlerinden arındırıldı; Tailwind sınıfları + semantic token’lar kullanılıyor.
- EntityGridTemplate varyant yöneticisi modalı ve aksiyonları tamamen Tailwind’e taşındı; Dropdown/Modal/Switch yerine native menü + custom switch kullanılıyor.
- EntityGridTemplate’te Ant `message` API’si yerine Tailwind tabanlı toast katmanı kullanılıyor; native `<select>` varyant seçici ve `env -u ELECTRON_RUN_AS_NODE npm run cypress:reports` guard/audit/variant senaryoları 2025-11-15 16:35 koşusunda yeşil doğrulandı.
- `mfe-shell` NotificationCenter bileşeni Ant `Badge/Drawer/List/Tag` bağımlılıklarından arındırıldı; Tailwind ikonlu buton + custom panel kartları kullanıyor.
- `mfe-users` UsersGrid sütunları (rol/durum/modül badge’leri) ve toolbar (quick filter, veri modu seçimi) Tailwind primitives ile yeniden yazıldı; Ant `Tag/Select/message` bağımlılıkları kaldırıldı.
- `mfe-access` BulkPermissionModal ve AccessRoleDrawer Tailwind modal/drawer bileşenleriyle yeniden inşa edildi; i18n sözlüklerine `access.drawer.permissionsEmpty` anahtarı eklendi, UI smoke testleri güncellendi.
- `mfe-access` AccessFilterBar, variant yöneticisi ve grid aksiyon butonları Tailwind formları + native `<select>` ile yeniden yazıldı; `AccessPage` içerisindeki tüm `Card/Button/Tooltip/Select/message` kullanımları shell toast bus’ı ve semantic sınıflarla değiştirildi.
- `mfe-users` UserDetailDrawer içindeki rol/süre/izin düzenleyicileri Tailwind input/select bileşenleriyle güncellendi; toast bildirimleri shell bus’ına yönlendirildi.
- Kalan `antd` kullanımları UI Kit’in `legacy` katmanında toplanacak; manifestlerde kullanılan formlar/toolbar’lar sıradaki flow’da taşınacak.

### Kalan Ant Design Kullanımları (2025-11-15)
- **mfe-shell**: NotificationCenter, register sayfası ve Shell dropdown’larında `Badge`, `Drawer`, `Dropdown`, `Typography` vb.
- **mfe-users**: UsersPage’deki legacy `Card/Spin/Button` düzeni ve advanced permission listeleri Tailwind’e taşınmayı bekliyor (grid toolbar + detail drawer tamamlandı).
- **mfe-access**: Manifest tabanlı story/smoke dokümantasyonu Ant varyant kartlarıyla eşleşiyor; Reporting/Users modülleriyle ortak primitives’e çekilecek.
- **mfe-reporting**: ReportPage giriş noktası hâlâ `Card`, `Space`, `Tabs` komponentlerini Ant üzerinden tüketiyor.
- **Legacy UI Kit paketleri**: `packages/ui-kit/src/legacy/*` altındaki Ant adapterları (ConfigProvider, Button) yalnız migration süreci için tutuluyor.
> Yukarıdaki alanlar bir sonraki flow’da Tailwind primitives’e geçirilmek üzere planlanacak.

## 1) Renk / Token Eşlemeleri

| UI Amacı       | Figma Token          | Tailwind (semantic)                 | Ant Design Token      | Not |
| -------------- | -------------------- | ----------------------------------- | --------------------- | --- |
| Birincil renk  | `brand/primary/500`  | `text-brand-500` / `bg-brand-500`   | `colorPrimary`        | Tailwind sınıfı `bg-brand-500` artık `var(--accent-default)` okur. |
| Birincil hover | `brand/primary/600`  | `text-brand-600`                    | `colorPrimaryHover`   | Hover state Tailwind `hover:bg-brand-600`. |
| Yüzey (bg)     | `surface/app`        | `bg-app-bg` (= `var(--surface-app)`) | `colorBgBase`         | Uygulama zemini. |
| Kart gövde     | `surface/raised/bg`  | `bg-card-bg shadow-card`           | `colorBgContainer`    | Gölge Tailwind `shadow-card` → `var(--shadow-elevated)`. |
| Sidebar aktif  | `surface/sidebar-active` | `bg-sidebar-active`              | `colorMenuItemBg`     | Token `--surface-sidebar-active`. |
| Sınır çizgisi  | `border/subtle`      | `border-subtle`                    | `colorBorder`         | CSS var `--border-subtle`. |
| Başlık rengi   | `text/heading`       | `text-text-primary`                | `colorTextHeading`    | Typography tokens. |
| Yardım metni   | `text/subtle`        | `text-text-muted`                  | `colorTextSecondary`  | — |
| Focus          | `focus/outline`      | `ring-focus`                       | `controlOutline`      | `ring-focus` = `var(--focus-outline)`. |
| Danger         | `semantic/danger/500`| `text-danger` / `bg-danger`        | `colorError`          | — |
| Warning        | `semantic/warning/500`| `text-warning` / `bg-warning`     | `colorWarning`        | — |
| Success        | `semantic/success/500`| `text-success` / `bg-success`     | `colorSuccess`        | — |

## 2) Spacing / Radius / Shadow

| Tasarım Tokenı | Tailwind           | Ant Design Değeri | Not |
| -------------- | ------------------ | ----------------- | --- |
| `space/2`      | `gap-2`, `p-2`     | `paddingXS`       | 8px |
| `space/4`      | `gap-4`, `px-4`    | `paddingSM`       | 16px |
| `space/6`      | `gap-6`, `py-6`    | `paddingMD`       | 24px |
| `radius/md`    | `rounded-md`       | `borderRadiusLG`  | 10px |
| `radius/lg`    | `rounded-lg`       | `borderRadiusXL`  | 14px |
| `shadow/md`    | `shadow-card`      | `boxShadowSecondary` | `var(--shadow-elevated)` ile eşlenir. |

## 3) Component Geçiş Sıralaması

1. **Primitives**
   - Button, Input, Select, Tag, Badge Ant Design kullanımını bırakıp Tailwind primitives’e taşınır.
   - `antd` temalı bileşenler UI Kit’te “legacy” klasörüne alınır.

2. **Layout / Shell**
   - `PageLayout`, `Sidebar`, `Topbar`, `FilterBar` Tailwind primitives kullanır.
   - Ant Design `Layout`, `Menu`, `Breadcrumb` sadece legacy ekranlarda tutulur.

3. **Pattern + Formlar**
   - Form yapıları, modallar ve drawer’lar `ui/primitives` katmanındaki Tailwind tabanlı karşılıklarla değiştirilir.

4. **Kaldırma**
   - Kod tabanında `import { Button } from 'antd'` gibi ifadeler bulunursa build kırmızıya çekilir (lint kuralı).

## 4) Kontroll listesi

- [ ] Yeni ekran Tailwind primitives kullanıyor mu?
- [ ] Semantic token adları (`surface.*`, `text.*`, `accent.*`) ile sınıflar eşleşiyor mu?
- [ ] `tailwind.config.js` içinde hex veya Ant Design token’ı kalmadı mı?
- [ ] Legacy ekranlar `legacy/` klasöründe etiketlendi mi?
- [ ] UI Kit paketinde Ant Design bağımlılığı kaldırıldı mı? (en geç Phase 2)

> Bu belge her akış sonunda gözden geçirilir. Son kalan Ant Design referansı giderildiğinde arşive taşınacaktır.
