import type { GridRequest, GridResponse } from './types';

export type FetchFn<T = any> = (req: GridRequest) => Promise<GridResponse<T>>;

export const createServerSideDatasource = <T = any>(fetchFn: FetchFn<T>) => {
  return {
    fetch: (req: GridRequest) => fetchFn(req),
  };
};

