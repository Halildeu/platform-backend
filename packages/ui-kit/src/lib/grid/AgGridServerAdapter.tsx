// Placeholder adapter for AG Grid Server-Side Row Model.
// The actual AG Grid dependency is not included in this repo.
// This component mirrors EntityGridTemplate props and can be
// replaced with a real AG Grid implementation without changing callers.
import React, { useCallback, useMemo, useRef, useState } from 'react';
import type { EntityGridTemplateProps, GridRequest, GridResponse, SortModelItem } from './types';

// NOTE: Requires dev deps: ag-grid-community, ag-grid-react
// CSS (theme) imports are expected to be handled by the bundler entry (shell app).
// import 'ag-grid-community/styles/ag-grid.css';
// import 'ag-grid-community/styles/ag-theme-quartz.css';

export function AgGridServerAdapter<T = any>(props: EntityGridTemplateProps<T>) {
  const { columns, gridId, fetchFn, toolbarSlots, detailDrawer, onRowAction, onRequestChange, onData, onGridApiReady } = props;
  const gridApiRef = useRef<any>(null);
  const [quickFilter, setQuickFilter] = useState<string>('');
  const [selectedRow, setSelectedRow] = useState<T | null>(null);

  const colDefs = useMemo(() => {
    return columns.map((c) => ({
      field: c.field,
      headerName: c.headerName ?? c.field,
      sortable: true,
      filter: resolveFilterType(c.filterType),
      width: c.width,
      flex: c.flex,
      suppressMenu: false,
    }));
  }, [columns]);

  const defaultColDef = useMemo(() => ({
    sortable: true,
    resizable: true,
    filter: true,
  }), []);

  const onGridReady = useCallback((params: any) => {
    gridApiRef.current = params.api;
    try { onGridApiReady?.(params.api); } catch {}
    // Server-side datasource binding
    const dataSource = {
      getRows: async (gridParams: any) => {
        try {
          if (!fetchFn) {
            gridParams.success({ rowData: [], rowCount: 0 });
            return;
          }
          const req: GridRequest = toGridRequest(gridParams.request);
          onRequestChange?.(req);
          const res: GridResponse<T> = await fetchFn(req);
          if (onData) onData(res);
          gridParams.success({ rowData: res.rows, rowCount: res.total });
        } catch (e) {
          gridParams.fail();
        }
      }
    };
    // v29+: setGridOption, legacy: setServerSideDatasource
    if (typeof params.api.setServerSideDatasource === 'function') {
      params.api.setServerSideDatasource(dataSource);
    } else if (typeof params.api.setGridOption === 'function') {
      params.api.setGridOption('serverSideDatasource', dataSource);
    }
  }, [fetchFn, onRequestChange]);

  const onQuickFilterChange = (val: string) => {
    // SSRM'de AG Grid quickFilterText desteklenmiyor; yalnız UI input state tutulur.
    setQuickFilter(val);
  };

  const onRowDoubleClicked = (e: any) => {
    if (onRowAction) {
      onRowAction('edit', e.data);
    } else {
      setSelectedRow(e.data);
    }
  };

  return (
    <div data-grid-id={gridId} style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        {toolbarSlots}
        <input
          aria-label="Quick filter"
          placeholder="Ara"
          value={quickFilter}
          onChange={(e) => onQuickFilterChange(e.target.value)}
          style={{ marginLeft: 'auto' }}
        />
      </div>

      <div className="ag-theme-quartz" style={{ height: '100%', width: '100%' }}>
        {/*
          We avoid importing AgGridReact here to keep this package light if AG Grid is not installed.
          The consuming app should alias this adapter to a real implementation or include AG Grid deps.
        */}
        {renderAgGridReact({ colDefs, defaultColDef, onGridReady, onRowDoubleClicked })}
      </div>

      {detailDrawer && (
        <div aria-live="polite">{detailDrawer(selectedRow)}</div>
      )}
    </div>
  );
}

function toGridRequest(req: any): GridRequest {
  const start = req.startRow ?? 0;
  const end = req.endRow ?? 50;
  const pageSize = Math.max(1, end - start);
  const page = Math.floor(start / pageSize) + 1;
  const sortModel: SortModelItem[] | undefined = Array.isArray(req.sortModel)
    ? req.sortModel.map((s: any) => ({ colId: s.colId, sort: s.sort }))
    : undefined;
  const filterModel = req.filterModel ?? undefined;
  return { page, pageSize, sortModel, filterModel };
}

function renderAgGridReact(opts: any) {
  let AgGridReactComp: any = null;
  try {
    // Dynamically require to avoid hard dependency when not installed
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const ag = require('ag-grid-react');
    AgGridReactComp = ag.AgGridReact;
  } catch (e) {
    return (
      <div style={{ padding: 12, color: '#d46b08' }}>
        AG Grid yüklü değil. Dev bağımlılıklara <code>ag-grid-community</code> ve <code>ag-grid-react</code> ekleyin.
      </div>
    );
  }

  return (
    <AgGridReactComp
      columnDefs={opts.colDefs}
      defaultColDef={opts.defaultColDef}
      rowModelType="serverSide"
      pagination={false}
      rowSelection="multiple"
      suppressRowClickSelection={true}
      // Advanced Filter etkin senaryolarda Filters Tool Panel desteklenmediği için kapalı.
      sideBar={{ toolPanels: ['columns'] }}
      animateRows
      onGridReady={opts.onGridReady}
      onRowDoubleClicked={opts.onRowDoubleClicked}
    />
  );
}

function resolveFilterType(type?: 'text' | 'number' | 'date' | 'set') {
  switch (type) {
    case 'number':
      return 'agNumberColumnFilter';
    case 'date':
      return 'agDateColumnFilter';
    case 'set':
      return 'agSetColumnFilter';
    case 'text':
    default:
      return 'agTextColumnFilter';
  }
}
