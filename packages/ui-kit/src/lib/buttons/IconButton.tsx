import React from 'react';

type Props = {
  title: string;
  onClick?: () => void;
  size?: number; // icon size
  disabled?: boolean;
  children: React.ReactNode; // icon
};

export function IconButton({ title, onClick, size = 16, disabled, children }: Props) {
  const btnSize = Math.max(24, size + 8);
  return (
    <button
      type="button"
      title={title}
      aria-label={title}
      onClick={onClick}
      disabled={disabled}
      style={{
        height: btnSize,
        width: btnSize,
        padding: 0,
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        border: '1px solid #d9d9d9',
        borderRadius: 6,
        background: disabled ? '#f5f5f5' : '#fff',
        cursor: disabled ? 'not-allowed' : 'pointer',
      }}
    >
      <span style={{ lineHeight: 0 }}>{children}</span>
    </button>
  );
}

