import React from 'react';

export interface TooltipProps {
  text: string;
  children: React.ReactNode;
  className?: string;
}

/**
 * Basit tooltip primitive'i.
 * Varsayılan olarak native `title` attribute'u kullanır; ileride daha zengin bir tooltip ile değiştirilebilir.
 */
export const Tooltip: React.FC<TooltipProps> = ({ text, children, className }) => {
  return (
    <span title={text} className={className}>
      {children}
    </span>
  );
};

export default Tooltip;

