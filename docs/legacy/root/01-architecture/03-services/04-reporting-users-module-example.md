---
title: "Örnek ReportModule — Users"
status: draft
owner: "@team/frontend"
last_review: 2025-11-12
---

Aşağıda `users` modülü için tam bir ReportModule örneği yer alır. Kontrat için bkz. `03-reporting-schema-contract.md`.

```ts
import type { ReportModule, ReportSchema } from './types';
import type { GridRequest, GridResponse } from 'mfe-ui-kit';

type UsersFilters = { search: string; status: 'ALL'|'ACTIVE'|'INACTIVE'|'INVITED'|'SUSPENDED' };
type UsersRow = {
  id: string; fullName: string; email: string; role: string; status: string;
  lastLoginAt?: string | null; createdAt?: string | null;
};

const schema: ReportSchema = {
  version: 1,
  columns: [
    { field: 'fullName', headerKey: 'reports.users.columns.fullName', flex: 1.4 },
    { field: 'email', headerKey: 'reports.users.columns.email', flex: 1.6 },
    { field: 'role', headerKey: 'reports.users.columns.role', width: 140 },
    { field: 'status', headerKey: 'reports.users.columns.status', width: 140 },
    { field: 'lastLoginAt', headerKey: 'reports.users.columns.lastLoginAt', flex: 1.2 },
    { field: 'createdAt', headerKey: 'reports.users.columns.createdAt', flex: 1.2 },
  ],
  filters: [
    { field: 'search', type: 'search', placeholderKey: 'reports.filters.search.placeholder' },
    {
      field: 'status', type: 'select', placeholderKey: 'reports.filters.status.placeholder',
      options: [
        { value: 'ALL', labelKey: 'reports.filters.all' },
        { value: 'ACTIVE', labelKey: 'reports.status.active' },
        { value: 'INACTIVE', labelKey: 'reports.status.inactive' },
        { value: 'INVITED', labelKey: 'reports.status.invited' },
        { value: 'SUSPENDED', labelKey: 'reports.status.suspended' },
      ]
    }
  ]
};

const resolveSearchSeed = (params?: URLSearchParams) => (params?.get('search') || params?.get('userId') || '').trim();
const resolveStatusSeed = (params?: URLSearchParams) => {
  const raw = (params?.get('status') || 'ALL').toUpperCase();
  return ['ALL','ACTIVE','INACTIVE','INVITED','SUSPENDED'].includes(raw) ? raw as UsersFilters['status'] : 'ALL';
};

export const usersReportModule: ReportModule<UsersFilters, UsersRow> = {
  id: 'reports.users',
  route: 'users',
  navKey: 'reports.nav.users',
  titleKey: 'reports.users.title',
  descriptionKey: 'reports.users.description',
  breadcrumbKeys: [ { key: 'reports.breadcrumb.root', to: '/reports' }, { key: 'reports.users.breadcrumb' } ],
  createInitialFilters: (params) => ({ search: resolveSearchSeed(params), status: resolveStatusSeed(params) }),
  schema,
  fetchRows: async (filters: UsersFilters, request: GridRequest): Promise<GridResponse<UsersRow>> => {
    const qs = new URLSearchParams();
    const search = (request.quickFilter || filters.search || '').trim();
    if (search) qs.set('search', search);
    if (filters.status && filters.status !== 'ALL') qs.set('status', filters.status);
    qs.set('page', String(request.page ?? 1));
    qs.set('pageSize', String(request.pageSize ?? 50));
    // basit sort map
    const sort = (request.sortModel || [])
      .filter(s => s.colId && s.sort)
      .map(s => `${s.colId === 'fullName' ? 'name' : s.colId},${s.sort}`)
      .join(';');
    if (sort) qs.set('sort', sort);

    const resp = await fetch(`/api/users/all?${qs.toString()}`, { headers: { 'Authorization': 'Bearer TOKEN' } });
    if (!resp.ok) throw new Error(`Users fetch failed (HTTP ${resp.status})`);
    const data = await resp.json();
    const items: UsersRow[] = Array.isArray(data.items) ? data.items : [];
    return { rows: items, total: typeof data.total === 'number' ? data.total : items.length };
  },
  toolbarActions: [
    {
      id: 'export-csv', labelKey: 'reports.toolbar.exportCsv', icon: 'csv',
      onClick: async ({ filters, request }) => {
        const qs = new URLSearchParams();
        if (filters.search) qs.set('search', filters.search);
        if (filters.status && filters.status !== 'ALL') qs.set('status', filters.status);
        const resp = await fetch(`/api/users/export.csv?${qs.toString()}`, {
          headers: { 'Authorization': 'Bearer TOKEN', 'Accept': 'text/csv' }
        });
        if (!resp.ok) throw new Error('Export failed');
        // blob indirme …
      }
    }
  ]
};
```

Notlar
- `createInitialFilters` deep-link senaryolarını (userId/search/status) karşılar.
- Sort alan eşlemesi (fullName→name) örnek olarak gösterilmiştir.
```

