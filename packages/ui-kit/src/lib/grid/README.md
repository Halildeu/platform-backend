# AgGridServerAdapter Kullanımı

Bu adapter, AG Grid SSRM (Server-Side Row Model) için hafif bir köprü sağlar. Pakete AG Grid bağımlılığı eklemez; çalışma anında `require('ag-grid-react')` denemesi yapar.

## Kurulum

Projede AG Grid’i kullanmak için dev bağımlılıkları ekleyin:

```
npm i -D ag-grid-community ag-grid-react
```

ve tema CSS’lerini entry dosyanıza import edin:

```
import 'ag-grid-community/styles/ag-grid.css';
import 'ag-grid-community/styles/ag-theme-quartz.css';
```

## Örnek

```
import { AgGridServerAdapter } from '@ui-kit';

<AgGridServerAdapter
  gridId="users"
  columns={[{ field: 'id' }, { field: 'email' }]}
  fetchFn={fetchUsers}
  onRequestChange={(req) => setLastReq(req)}
  onRowAction={(action, row) => openDrawer(row)}
/>
```

- `columns.filterType`: `'text' | 'number' | 'date' | 'set'`
- Menü/panel: Column Tool Panel varsayılan olarak açıktır (`sideBar: ['columns']`).

## Gelişmiş Filtre (advancedFilter) — Whitelist ile tip güvenliği

BE ile uyumlu alan/operatör whitelist’ini UI’da kullanarak yanlış kombinasyonları engelleyebilirsiniz.

```
import {
  defaultUsersAdvancedFilterSchema,
  allowedOperatorsForField,
  validateAdvancedFilter,
  buildAdvancedFilterParam,
} from '@ui-kit';

const schema = defaultUsersAdvancedFilterSchema; // veya kendi şemanız

// Alan seçilince uygun operatörler
const ops = allowedOperatorsForField(schema, selectedField);

// Modeli doğrula ve paramı üret
const model = { logic: 'and', conditions: [ { field: 'email', op: 'contains', value: '@' } ] };
const v = validateAdvancedFilter(schema, model);
if (!v.ok) { /* kullanıcıya hata göster */ }
const advancedFilter = buildAdvancedFilterParam(model);
// GET /api/users/all?...&advancedFilter=${advancedFilter}
```

