---
title: "Reporting – Tailwind Layout & Storybook Notları"
status: draft
owner: "@team/frontend"
last_review: 2025-11-15
---

> Reporting MFE’nin grid sayfaları artık tamamen Tailwind tabanlı `PageLayout + ReportFilterPanel + EntityGridTemplate` kombinasyonu ile render ediliyor. Bu rehber yeni düzenin kodla nasıl eşleştiğini, Storybook’ta nasıl belgeleneceğini ve hiyerarşik sekme (tab) örneklerini özetler.

## 1) Layout Özet

- Dosya: `frontend/apps/mfe-reporting/src/app/reporting/ReportPage.tsx`
- Ant `Card/Button/Space/message` bağımlılıkları kaldırıldı; aksiyonlar native `<button>` ile, kart ise `rounded-3xl border border-slate-200 bg-white p-6 shadow-sm` yapısı ile oluşturuluyor.
- Hata/başarı mesajları shell’in `app:toast` event bus’ı üzerinden tetikleniyor (`showToast` helper).
- Grid toolbar’ı `EntityGridTemplate`’in `toolbarExtras` slotu ile yönetiliyor; export butonu Tailwind butonuna taşındı.

## 2) Storybook Senaryosu

Storybook’ta bu düzeni belgelemek için (ör. Foundations → Reporting Layout) aşağıdaki hikâyeyi ekleyin:

```tsx
// frontend/apps/mfe-reporting/src/stories/ReportPage.stories.tsx
import type { Meta, StoryObj } from '@storybook/react';
import { ReportPage } from '../app/reporting/ReportPage';
import { mockReportModule } from './__mocks__/report-module';

const meta: Meta<typeof ReportPage> = {
  title: 'Reporting/ReportPage',
  component: ReportPage,
  parameters: { layout: 'fullscreen' },
};

export default meta;

export const Default: StoryObj<typeof ReportPage> = {
  args: {
    module: mockReportModule({
      titleKey: 'reports.example.title',
      descriptionKey: 'reports.example.description',
    }),
  },
};
```

`mockReportModule` basit bir server response taklidi ve hiyerarşik tab örneğini içerir (bkz. aşağıdaki örnek). Storybook çalıştırma komutu: `npm run storybook -- --docs`.

## 3) Hiyerarşik Tab Örneği

Hierarchical tab (örn. Aylık Raporlar → Alt kategori) için `ReportModule.renderFilters` fonksiyonuna aşağıdaki pattern eklenebilir:

```tsx
const tabs = [
  { key: 'financial', label: 'Finansal' },
  { key: 'operations', label: 'Operasyon' },
];

renderFilters: ({ values, setFieldValue, submit }) => (
  <div className="flex flex-col gap-4">
    <div className="flex gap-2 rounded-2xl border border-slate-200 bg-slate-50 p-2">
      {tabs.map((tab) => (
        <button
          key={tab.key}
          type="button"
          className={`rounded-xl px-3 py-1 text-sm font-semibold ${
            values.scope === tab.key ? 'bg-brand-600 text-white' : 'text-slate-600'
          }`}
          onClick={() => {
            setFieldValue('scope', tab.key);
            submit();
          }}
        >
          {tab.label}
        </button>
      ))}
    </div>
    {/* Tab içeriği burada */}
  </div>
);
```

Bu yapı Storybook senaryosunda görünür, QA ve tasarım ekipleri hiyerarşi davranışını tailwind sınıflarıyla inceleyebilir.

## 4) QA / Playwright / Chromatic

- Tailwind düzenine geçtiğimiz tarihte `env -u ELECTRON_RUN_AS_NODE npm run cypress:reports` ve `npm run smoke:playwright` komutları yeşil koştu (bkz. session log 2025‑11‑15 17:06).
- Storybook’tan alınan hiyerarşik tab hikâyesi Chromatic’e bağlanmak için `npx chromatic --project-token $CHROMATIC_PROJECT_TOKEN --storybook-base-dir storybook-static` komutu kullanılabilir. Pipeline her PR’da running screenshot/regression üretir; sonuçlar acceptance log’una eklenir.
- Playwright senaryosu `tests/playwright/reporting.a11y.spec.ts` hem guard redirect’lerini hem de Axe a11y kontrollerini doğrular; pipeline script’i `npm run smoke:playwright` bu dosyayı çalıştırır.

## 5) İlgili Dosyalar

- `frontend/apps/mfe-reporting/src/app/reporting/ReportPage.tsx`
- `frontend/apps/mfe-reporting/src/modules/*` (renderFilters/renderDetail örnekleri)
- `docs/05-governance/02-roadmap/acceptance/ALPHA-04.md`
- `docs/01-architecture/01-system/02-frontend-architecture.md`

Bu rehber ilerleyen akışlarda hiyerarşik tab davranışı veya yeni layout varyantları eklendiğinde güncellenecektir.
