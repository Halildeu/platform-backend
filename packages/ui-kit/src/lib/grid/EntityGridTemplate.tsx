import React, { useEffect, useMemo, useState } from 'react';
import type { EntityGridTemplateProps, GridRequest, GridResponse, SortModelItem } from './types';

export function EntityGridTemplate<T = any>(props: EntityGridTemplateProps<T>) {
  const {
    columns,
    gridId,
    fetchFn,
    toolbarSlots,
    toolbarExtras,
    detailDrawer,
    onRowAction,
    onRequestChange,
    reloadSignal,
  } = props;
  const resolvedToolbar = toolbarExtras ?? toolbarSlots;

  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(50);
  const [sortModel, setSortModel] = useState<SortModelItem[] | undefined>(undefined);
  const [filterModel, setFilterModel] = useState<Record<string, unknown> | undefined>(undefined);
  const [quickFilter, setQuickFilter] = useState<string | undefined>(undefined);
  const [data, setData] = useState<GridResponse<T>>({ rows: [], total: 0 });
  const [selectedRow, setSelectedRow] = useState<T | null>(null);
  const [loading, setLoading] = useState(false);

  const effectiveFetch = fetchFn ?? (() => Promise.resolve({ rows: [], total: 0 }));

  const request: GridRequest = useMemo(
    () => ({ page, pageSize, sortModel, filterModel, quickFilter }),
    [page, pageSize, sortModel, filterModel, quickFilter]
  );

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    effectiveFetch(request)
      .then((res) => {
        if (!cancelled) setData(res);
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [effectiveFetch, request, reloadSignal]);

  useEffect(() => {
    onRequestChange?.(request);
  }, [onRequestChange, request]);

  useEffect(() => {
    if (typeof props.onGridReady === 'function') {
      const api = {
        refreshClientSideRowModel: () => undefined,
        refreshServerSide: () => undefined,
      };
      try {
        props.onGridReady({ api });
      } catch {}
    }
  }, [props.onGridReady]);

  return (
    <div data-grid-id={gridId} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        {resolvedToolbar}
        <input
          aria-label="Quick filter"
          placeholder="Ara"
          value={quickFilter ?? ''}
          onChange={(e) => setQuickFilter(e.target.value || undefined)}
          style={{ marginLeft: 'auto' }}
        />
      </div>

      <div role="table" aria-busy={loading} style={{ border: '1px solid #eee' }}>
        <div role="row" style={{ display: 'flex', background: '#fafafa', fontWeight: 600 }}>
          {columns.map((c, index) => {
            const colId = c.field ?? `col-${index}`;
            return (
              <div key={colId} role="columnheader" style={{ flex: c.flex ?? 1, padding: 8 }}>
                {c.headerName ?? colId}
                <button
                  type="button"
                  aria-label={`Sort ${colId} asc`}
                  onClick={() => setSortModel([{ colId: colId, sort: 'asc' }])}
                  style={{ marginLeft: 6 }}
                >
                  ↑
                </button>
                <button
                  type="button"
                  aria-label={`Sort ${colId} desc`}
                  onClick={() => setSortModel([{ colId: colId, sort: 'desc' }])}
                  style={{ marginLeft: 4 }}
                >
                  ↓
                </button>
              </div>
            );
          })}
          <div style={{ width: 80 }} />
        </div>
        {data.rows.map((row: any, idx: number) => (
          <div key={idx} role="row" style={{ display: 'flex', borderTop: '1px solid #f0f0f0' }}>
            {columns.map((c, colIdx) => {
              const colId = c.field ?? `col-${colIdx}`;
              return (
                <div key={colId} role="cell" style={{ flex: c.flex ?? 1, padding: 8 }}>
                  {String(row[colId] ?? '')}
                </div>
              );
            })}
            <div style={{ width: 80, padding: 8, display: 'flex', gap: 6 }}>
              <button type="button" onClick={() => setSelectedRow(row)}>Detay</button>
              {onRowAction && (
                <button type="button" onClick={() => onRowAction('edit', row)}>Düzenle</button>
              )}
            </div>
          </div>
        ))}
      </div>

      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <span>Toplam: {data.total}</span>
        <button type="button" disabled={page <= 1} onClick={() => setPage((p) => Math.max(1, p - 1))}>
          Önceki
        </button>
        <span>Sayfa {page}</span>
        <button
          type="button"
          disabled={page * pageSize >= data.total}
          onClick={() => setPage((p) => p + 1)}
        >
          Sonraki
        </button>
        <select value={pageSize} onChange={(e) => setPageSize(Number(e.target.value))}>
          <option value={25}>25</option>
          <option value={50}>50</option>
          <option value={100}>100</option>
        </select>
      </div>

      {typeof detailDrawer === 'function' && (
        <div aria-live="polite">{detailDrawer(selectedRow)}</div>
      )}
    </div>
  );
}
