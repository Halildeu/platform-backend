import React, { useEffect, useMemo, useState } from 'react';
import { onNotify } from '../notify/notify';

export interface PageBreadcrumbItem {
  title?: React.ReactNode;
  path?: string;
}

export interface PageLayoutProps {
  title?: React.ReactNode;
  subtitle?: React.ReactNode;
  description?: React.ReactNode;
  breadcrumbItems?: PageBreadcrumbItem[];
  actions?: React.ReactNode;
  /**
   * @deprecated `actions` prop kullanın.
   */
  headerExtra?: React.ReactNode;
  /** Tam ekran özelliğini aktif eder (varsayılan: true) */
  enableFullscreen?: boolean;
  /** Üst başlık alanını sayfa içinde yapışkan (sticky) tutar (varsayılan: true) */
  stickyHeader?: boolean;
  /** Sticky başlık için üstten offset (px). Örn: shell header yüksekliği. */
  stickyHeaderOffset?: number;
  /**
   * Sayfa gövdesinin kalan yüksekliği doldurmasını sağlar.
   * Üstte shell header varsa, uygulama kökünün 100% yükseklikte olması gerekir.
   */
  fullHeight?: boolean;
  /** Görsel ayırt edicilik için kırmızı kenarlıklar (yalnızca debug/özel sayfalar için) */
  debugBorder?: boolean;
  /** Başlık açıklamasını yalnızca hover/focus olduğunda göster */
  descriptionRevealOnHover?: boolean;
  children: React.ReactNode;
}

export function PageLayout({
  title,
  subtitle,
  description,
  breadcrumbItems,
  actions,
  headerExtra,
  enableFullscreen = true,
  stickyHeader = true,
  stickyHeaderOffset = 0,
  fullHeight,
  debugBorder = false,
  descriptionRevealOnHover = false,
  children,
}: PageLayoutProps) {
  const [fullscreen, setFullscreen] = useState(false);
  const [isHeaderHovered, setIsHeaderHovered] = useState(false);
  const resolvedHeaderActions = actions ?? headerExtra ?? null;
  const resolvedDescription = description ?? subtitle ?? null;
  const showDescription = !descriptionRevealOnHover || isHeaderHovered;

  const breadcrumbTrail = useMemo(() => {
    if (!breadcrumbItems?.length) {
      return null;
    }
    const lastIndex = breadcrumbItems.length - 1;
    return (
      <nav
        aria-label="breadcrumb"
        style={{
          fontSize: 13,
          color: '#8c8c8c',
          display: 'flex',
          flexWrap: 'wrap',
          gap: 4,
          alignItems: 'center',
          marginBottom: 6,
        }}
      >
        {breadcrumbItems.map((item, index) => {
          const separator = index < lastIndex ? (
            <span key={`sep-${index}`} style={{ margin: '0 4px' }}>
              /
            </span>
          ) : null;
          const content =
            item.path && !Object.is(index, lastIndex) ? (
              <a
                key={item.path ?? `crumb-${index}`}
                href={item.path}
                style={{ color: '#8c8c8c' }}
              >
                {item.title ?? item.path}
              </a>
            ) : (
              <span key={item.path ?? `crumb-${index}`}>
                {item.title ?? item.path}
              </span>
            );
          return (
            <React.Fragment key={item.path ?? `crumb-${index}`}>
              {content}
              {separator}
            </React.Fragment>
          );
        })}
      </nav>
    );
  }, [breadcrumbItems]);

  useEffect(() => {
    document.body.classList.toggle('app-page-fullscreen', fullscreen);
    return () => {
      document.body.classList.remove('app-page-fullscreen');
    };
  }, [fullscreen]);

  const containerStyle = useMemo<React.CSSProperties>(() => ({
    display: 'flex',
    flexDirection: 'column',
    gap: 12,
    padding: 16,
    height: fullHeight ? '100%' : undefined,
    minHeight: '100vh',
    position: 'relative',
    overflowY: fullHeight ? 'auto' : undefined,
    background: '#fff',
    border: debugBorder ? '2px solid #ff4d4f' : undefined,
    borderRadius: debugBorder ? 12 : undefined,
    boxShadow: debugBorder ? '0 0 0 2px rgba(255,77,79,0.1)' : undefined,
  }), [fullHeight, debugBorder]);

  const headerContainerStyle = useMemo<React.CSSProperties>(() => {
    const base: React.CSSProperties = {
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      paddingBottom: 8,
      borderBottom: '1px solid #f0f0f0',
      position: stickyHeader ? 'sticky' as const : 'relative' as const,
      top: stickyHeader ? stickyHeaderOffset : undefined,
      zIndex: 5,
      background: '#fff',
      boxShadow: stickyHeader ? '0 1px 0 rgba(0,0,0,0.05)' : undefined,
    };

    if (debugBorder) {
      Object.assign(base, {
        padding: 12,
        paddingBottom: 12,
        border: '2px solid #ff4d4f',
        borderRadius: 10,
        borderBottom: '2px solid #ff4d4f',
        boxShadow: '0 0 0 2px rgba(255,77,79,0.12)',
        background: '#fff5f5',
      });
    }

    return base;
  }, [debugBorder, stickyHeader, stickyHeaderOffset]);

  const headerTitleWrapperStyle = useMemo<React.CSSProperties>(() => ({
    flex: 1,
    minWidth: 0,
    borderLeft: debugBorder ? '3px solid #ff4d4f' : undefined,
    paddingLeft: debugBorder ? 12 : 0,
  }), [debugBorder]);

  const descriptionStyle = useMemo<React.CSSProperties>(() => {
    const base: React.CSSProperties = {
      color: '#8c8c8c',
      marginTop: 4,
    };
    if (!descriptionRevealOnHover) {
      return base;
    }
    return {
      ...base,
      marginTop: showDescription ? 4 : 0,
      maxHeight: showDescription ? 80 : 0,
      opacity: showDescription ? 1 : 0,
      overflow: 'hidden',
      transition: 'opacity 0.2s ease, max-height 0.2s ease, margin-top 0.2s ease',
      pointerEvents: showDescription ? 'auto' : 'none',
    };
  }, [descriptionRevealOnHover, showDescription]);

  useEffect(() => {
    if (!descriptionRevealOnHover) {
      setIsHeaderHovered(false);
    }
  }, [descriptionRevealOnHover]);

  const headerInteractionProps = descriptionRevealOnHover
    ? {
        onMouseEnter: () => setIsHeaderHovered(true),
        onMouseLeave: () => setIsHeaderHovered(false),
        onFocus: () => setIsHeaderHovered(true),
        onBlur: (event: React.FocusEvent<HTMLDivElement>) => {
          const nextTarget = event.relatedTarget as Node | null;
          if (!nextTarget || !event.currentTarget.contains(nextTarget)) {
            setIsHeaderHovered(false);
          }
        },
        tabIndex: 0,
        role: 'region' as const,
        'aria-expanded': showDescription,
        'aria-label': typeof title === 'string' ? title : undefined,
      }
    : {};

  return (
    <div style={containerStyle}>
      <PageNotifications />
      {!fullscreen && (
        <div
          style={headerContainerStyle}
          {...headerInteractionProps}
        >
          <div style={headerTitleWrapperStyle}>
            {breadcrumbTrail}
            {title && (
              <h2 style={{ margin: 0, lineHeight: 1.3 }}>
                {typeof title === 'string' ? title : title}
              </h2>
            )}
            {resolvedDescription && (
              <div style={descriptionStyle}>{resolvedDescription}</div>
            )}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {resolvedHeaderActions}
            {enableFullscreen && (
              <button
                type="button"
                aria-label="Tam ekranda göster"
                title="Tam ekran"
                onClick={() => setFullscreen(true)}
                style={{ padding: '4px 8px' }}
              >
                ⛶
              </button>
            )}
            {debugBorder && (
              <span style={{ background: '#ff4d4f', color: '#fff', padding: '2px 6px', borderRadius: 4, fontSize: 12 }}>
                DEBUG
              </span>
            )}
          </div>
        </div>
      )}

      <div style={{ position: 'relative', zIndex: 0, flex: fullHeight ? 1 : undefined, minHeight: fullHeight ? 0 : undefined, border: debugBorder ? '2px solid #ff4d4f' : undefined, borderRadius: debugBorder ? 6 : 0 }}>
        {fullscreen && enableFullscreen && (
          <button
            type="button"
            aria-label="Tam ekrandan çık"
            title="Küçült"
            onClick={() => setFullscreen(false)}
            style={{ position: 'absolute', top: 8, right: 8, zIndex: 2, padding: '4px 8px' }}
          >
            ⤢
          </button>
        )}
        {children}
      </div>
    </div>
  );
}

function PageNotifications() {
  const [message, setMessage] = useState<string | null>(null);
  const [link, setLink] = useState<string | undefined>(undefined);

  useEffect(() => {
    const off = onNotify((n) => {
      setMessage(n.message);
      setLink(n.link);
      // auto hide after 4s
      window.setTimeout(() => setMessage(null), 4000);
    });
    return off;
  }, []);

  if (!message) return null;

  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        position: 'fixed',
        top: 12,
        right: 12,
        zIndex: 9999,
        background: '#052b4e',
        color: 'white',
        padding: '8px 12px',
        borderRadius: 6,
        boxShadow: '0 4px 10px rgba(0,0,0,0.2)',
        fontSize: 13,
        maxWidth: 360,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span>ℹ️</span>
        <span style={{ lineHeight: 1.25 }}>{message}</span>
      </div>
      {link && (
        <div style={{ marginTop: 6 }}>
          <a href={link} style={{ color: '#91d5ff', textDecoration: 'underline' }}>
            Audit’te aç
          </a>
        </div>
      )}
    </div>
  );
}
