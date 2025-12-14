import React from 'react';
let TextComp: any = null;
try {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  TextComp = require('@ant-design/icons').FileTextOutlined;
} catch {}

export function CsvIcon({ size = 14 }: { size?: number }) {
  if (TextComp) {
    return <TextComp style={{ fontSize: size, color: '#0958d9' }} />;
  }
  return <span style={{ fontSize: size, color: '#0958d9' }}>CSV</span>;
}
