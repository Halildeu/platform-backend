import React from 'react';
let ExcelComp: any = null;
try {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  ExcelComp = require('@ant-design/icons').FileExcelOutlined;
} catch {}

export function ExcelIcon({ size = 14 }: { size?: number }) {
  if (ExcelComp) {
    return <ExcelComp style={{ fontSize: size, color: '#237804' }} />;
  }
  return <span style={{ fontSize: size, color: '#237804' }}>XLS</span>;
}
