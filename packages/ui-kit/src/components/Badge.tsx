import React from 'react';
import clsx, { type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export type BadgeTone = 'default' | 'info' | 'success' | 'warning' | 'danger' | 'muted';

export interface BadgeProps extends React.HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone;
  children: React.ReactNode;
}

const toneClassNames: Record<BadgeTone, string> = {
  default: 'border-slate-200 bg-slate-50 text-slate-700',
  info: 'border-sky-200 bg-sky-50 text-sky-700',
  success: 'border-emerald-200 bg-emerald-50 text-emerald-700',
  warning: 'border-amber-200 bg-amber-50 text-amber-800',
  danger: 'border-rose-200 bg-rose-50 text-rose-700',
  muted: 'border-slate-200 bg-white text-slate-400',
};

export const Badge: React.FC<BadgeProps> = ({ tone = 'default', className, children, ...rest }) => {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold leading-tight',
        toneClassNames[tone],
        className,
      )}
      {...rest}
    >
      {children}
    </span>
  );
};

export default Badge;

