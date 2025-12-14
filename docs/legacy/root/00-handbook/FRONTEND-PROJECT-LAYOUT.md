## Frontend Monorepo Yapı İlkeleri

Bu doküman frontend monorepo (MFE + UI Kit) klasör yapısını ve yeni modüllerin nerede konumlanacağını özetler.

- Bu doküman **kanonik kaynaktır**; frontend layout özetleri buraya referans verir (detaylı sürüm frontend repo içinde).
- Kapsam: `frontend/` repo, apps (mfe-shell, mfe-users, mfe-access, mfe-reporting, vb.) + packages (ui-kit, shared-types, i18n-dicts).
- Kod kalitesi/stil: `docs/00-handbook/STYLE-FE-001.md`
- Mimari yerleşim: Shell/MFE mimarisi `frontend/docs/01-architecture/01-shell/01-frontend-architecture.md`
- UI Kit modeli: **tek resmi kaynak** `packages/ui-kit` paketidir; `mf_ui_kit` remote sadece demo/story amaçlıdır, runtime kodda kullanılmaz.

### Kök yapı (özet)
```
frontend/
  apps/
    mfe-shell/
    mfe-reporting/
    mfe-users/
    mfe-access/
    mfe-audit/
  packages/
    ui-kit/
    shared-types/
    i18n-dicts/
  docs/
  scripts/
  ci/
```

### MFE içi iskelet (örnek)
```
apps/<mfe>/src/
  app/            # Router, Theme/i18n Provider, ShellServices entegrasyonu
  manifest/       # Sayfa manifestleri + Zod/JSON schema
  i18n/           # Locale çözümü, sözlük yükleme/cache
  features/       # Kullanıcı/rol/rapor vb. domaine özel mantık
  widgets/        # UI komponzisyonları
  styles/         # Tema/Tailwind yardımcıları
```

### UI Kit Kaynağı
- `packages/ui-kit` monorepo paketi tasarım sistemi, tema token’ları, ErrorBoundary, grid-variants helper’ları ve ortak form bileşenlerinin **tek resmi kaynağıdır** (`QLTY-MF-UIKIT-01` Story’si).
- Runtime kodu bu paket üzerinden import eder; `apps/mf_ui_kit` remote yalnızca demo/story ve dokümantasyon amacıyla tutulur.
- Paylaşılan component/token güncellemeleri önce paket içinde yapılır, ardından MFE’ler `pnpm install`/`pnpm update` ile aynı sürüme çekilir; FRONTEND-ARCH-STATUS “UI Kit Paket Modeli” bölümü bu akışı ayrıntılandırır.

### Kaynaklar
- Grid/SSRM stratejisi: `frontend/docs/ag-grid-ssrm-export-strategy.md`
- UX/layout rehberleri: `frontend/docs/architecture/frontend/ux/*`

Orijinal ayrıntılı doküman: `frontend/docs/architecture/frontend/frontend-project-layout.md`. Bu kopya handbook içinde hızlı erişim içindir; güncellemeler orijinal dokümana yansıtılmalıdır.
