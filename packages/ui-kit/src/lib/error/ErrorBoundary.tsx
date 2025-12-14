import React from 'react';

export type ErrorBoundaryProps = {
  fallback?: React.ReactNode;
  onError?: (error: unknown, info?: { componentStack?: string }) => void;
  children: React.ReactNode;
};

type ErrorBoundaryState = { hasError: boolean };

export class ErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true };
  }

  componentDidCatch(error: unknown, info: { componentStack?: string }) {
    if (this.props.onError) this.props.onError(error, info);
    // eslint-disable-next-line no-console
    console.error('[ErrorBoundary]', error, info?.componentStack);
  }

  render() {
    if (this.state.hasError) {
      return (
        this.props.fallback ?? (
          <div style={{ padding: 24 }}>
            <h3>Bir hata oluştu</h3>
            <p>Lütfen sayfayı yenileyin veya daha sonra tekrar deneyin.</p>
          </div>
        )
      );
    }
    return this.props.children;
  }
}

