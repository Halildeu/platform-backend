---
title: "Reporting Çekirdeği — Şema & Kontrat"
status: draft
owner: "@team/frontend"
last_review: 2025-11-12
---

Amaç
- Rapor modüllerinin ortak şema/kontratını tanımlamak: kolonlar, filtreler, toolbar aksiyonları, grid istek/yanıt haritası ve varyant yönetimi.

Modül Sözleşmesi
```ts
type TranslateFn = (key: string, params?: Record<string, unknown>) => string;

interface ReportFilterSchema {
  field: string;
  type: 'search' | 'text' | 'select' | 'dateRange';
  placeholderKey?: string;
  labelKey?: string;
  options?: Array<{ value: string; labelKey: string }>;
}

interface ReportColumnSchema {
  field: string;
  headerKey: string;
  width?: number;
  flex?: number | null;
  filterType?: 'text' | 'number' | 'date' | 'set';
}

interface ReportSchema {
  version: number;
  columns: ReportColumnSchema[];
  filters?: ReportFilterSchema[];
}

interface ReportModule<TFilters extends Record<string, unknown>, TRow> {
  id: string;                 // gridId (örn. reports.users)
  route: string;              // /reports/<route>
  titleKey: string;           // i18n key
  descriptionKey: string;     // i18n key
  breadcrumbKeys: Array<{ key: string; to?: string }>;
  navKey: string;             // menüde i18n key
  createInitialFilters: (params?: URLSearchParams) => TFilters; // deep-link binding
  renderFilters?: (ctx: { form: FormInstance<TFilters>; submit: () => void; t: TranslateFn }) => ReactNode;
  getColumns?: (t: TranslateFn) => ColumnDef<TRow>[];
  fetchRows: (filters: TFilters, request: GridRequest) => Promise<GridResponse<TRow>>;
  renderDetail?: (row: TRow | null, t: TranslateFn) => ReactNode;
  toolbarActions?: Array<{
    id: string; labelKey: string; icon?: 'csv' | 'json' | 'download';
    onClick: (ctx: { filters: TFilters; request: GridRequest; t: TranslateFn; loading: boolean }) => Promise<void> | void;
    successMessageKey?: string; errorMessageKey?: string; disabledWhenLoading?: boolean;
  }>;
  schema?: ReportSchema;
}
```

Grid Request/Yanıt Eşlemesi
- `GridRequest`: `page`, `pageSize`, `quickFilter` (UI), `sortModel` (çoklu sütun)
- API QS: `page`, `pageSize`, `search`, `status`, `sort` (`field,dir;...`)
- `GridResponse<TRow>`: `{ rows: TRow[], total: number }`

Deep-link Kuralları
- Canonical: `/admin/reports/<module>?userId=<id>&search=<q>&status=<code>&variant=<id>`
- `createInitialFilters(params)` fonksiyonu `URLSearchParams`’ten başlangıç filtrelerini üretir.

Varyant Yönetimi
- GridId = `module.id` (örn. `reports.users`)
- Listeleme: `/api/variants?gridId=<gridId>`
- Tercih: `PATCH /api/variants/{id}/preference { isSelected: true }`
- Query param override: `?variant=<id>` UI’da dropdown state’e önceliklidir.

Toolbar/Export
- CSV export aksiyonu `downloadWithAuth()` ile tetiklenir; başlıklar: `Authorization`, `Accept: text/csv`

Hatalar
- 401/403: yetki/guard engeline takılır; guard panoları (Loki/Prometheus) ile izlenir.

