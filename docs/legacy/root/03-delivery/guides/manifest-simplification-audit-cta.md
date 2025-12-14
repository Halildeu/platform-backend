---
title: "MFE Manifest Sadeleştirme + Audit CTA Akışları"
status: draft
owner: "@team/frontend"
last_review: 2025-11-12
---

Hedef
- `mfe-users` ve `mfe-access` manifestlerinde ortak alanları sadeleştirmek, audit CTA akışlarını standartlaştırmak (auditId link/toast) ve shell notify ile hizalamak.

Sadeleştirme İlkeleri
- Ortak paylaşımlar (react, react-dom, router) root/shell tarafından single source olarak yönetilir.
- Rota tanımları sadece remote tarafında; alias/guard yönlendirmeleri shell’de tutulur.
- Yerel konfig anahtarları (ör. page title, breadcrumb) i18n key’lerle ifade edilir.

Audit CTA Akışları
1) Mutasyon yanıtında `auditId` döndür (BE sözleşmesi).
2) FE tarafında shell servisinden `notify.push` çağır:
   - `meta.auditId` + `route: '/audit/events'` alanı zorunlu.
   - `meta.open=true` verildiğinde Notification Center otomatik açılır.
   - Toast mesajı shell tarafından üretildiği için ek `window` event’i gerekmiyor.

Örnek
```ts
import { getShellServices } from 'mfe_shell/services';

const notifyAuditSuccess = (auditId?: string) => {
  if (!auditId) return;
  getShellServices().notify.push({
    message: 'Kullanıcı durumu güncellendi.',
    description: `Audit ID: ${auditId}`,
    type: 'success',
    meta: {
      auditId,
      route: '/audit/events',
      action: 'users.toggle_activation',
      open: true,
    },
  });
};
```

Manifest Notları
- Remote entry ve shared bağımlılıklar dışındaki tüm alanlar min konfig ile tutulur; toolbar/CTA bileşenleri UI Kit’ten sağlanır.
- Audit linkleri tek sözleşme: `/admin/audit/events?auditId=`

Kabul
- Başarılı mutasyonlarda toast ve audit deep-link çalışır.
- Shell Notification Center ile tutarlı görünüm.
