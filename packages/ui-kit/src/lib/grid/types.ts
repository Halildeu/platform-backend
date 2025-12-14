import type { ReactNode } from 'react';

export type SortDirection = 'asc' | 'desc';

export type SortModelItem = {
  colId: string;
  sort: SortDirection;
};

export type FilterModel = Record<string, unknown>;

export type GridRequest = {
  page: number;
  pageSize: number;
  sortModel?: SortModelItem[];
  filterModel?: FilterModel;
  quickFilter?: string;
};

export type GridResponse<T = any> = {
  rows: T[];
  total: number;
};

export type ColumnDef = {
  field?: string;
  headerName?: string;
  width?: number;
  flex?: number | null;
  /**
   * İpucu: AG Grid tarafında filtre tipini seçmek için kullanılır.
   * 'text' | 'number' | 'date' | 'set'
   */
  filterType?: 'text' | 'number' | 'date' | 'set';
};

export type GridExportFormat = {
  type: 'csv' | 'excel';
  label: string;
  disabled?: boolean;
};

export type GridExportConfig<T = any> = {
  fileBaseName?: string;
  sheetName?: string;
  formats?: GridExportFormat[];
  processCellCallback?: (params: any) => unknown;
  csvColumnSeparator?: string;
  csvBom?: boolean;
};

export type EntityGridTemplateProps<T = any> = {
  columns: ColumnDef[];
  gridId: string;
  fetchFn?: (req: GridRequest) => Promise<GridResponse<T>>;
  toolbarSlots?: ReactNode;
  detailDrawer?: (row: T | null) => ReactNode;
  onRowAction?: (action: string, row: T) => void;
  onRequestChange?: (req: GridRequest) => void;
  reloadSignal?: number;
  onData?: (res: GridResponse<T>) => void;
  onGridApiReady?: (api: any) => void;
  onGridReady?: (event: any) => void;
  gridSchemaVersion?: number;
  gridOptions?: unknown;
  exportConfig?: GridExportConfig<T>;
  onRowDoubleClick?: (row: T) => void;
  isFullscreen?: boolean;
  onRequestFullscreen?: () => void;
  dataSourceMode?: 'client' | 'server';
  rowData?: T[];
  total?: number;
  createServerSideDatasource?: (...args: any[]) => unknown;
  toolbarExtras?: ReactNode;
};
