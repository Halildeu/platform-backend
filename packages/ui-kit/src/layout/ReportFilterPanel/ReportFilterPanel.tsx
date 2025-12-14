import React from 'react';

export interface FormInstance<T extends Record<string, unknown>> {
  setFieldsValue: (values: Partial<T>) => void;
  resetFields: () => void;
  getFieldsValue: (allFields?: boolean) => T;
}

export interface ReportFilterPanelProps<T extends Record<string, unknown>> {
  form: FormInstance<T>;
  initialValues?: Partial<T>;
  loading?: boolean;
  submitLabel?: string;
  resetLabel?: string;
  onSubmit: (values: T) => void;
  onReset?: () => void;
  children: React.ReactNode;
}

export function ReportFilterPanel<T extends Record<string, unknown>>({
  form,
  initialValues,
  loading,
  submitLabel = 'Filtrele',
  resetLabel = 'Sıfırla',
  onSubmit,
  onReset,
  children,
}: ReportFilterPanelProps<T>) {
  const handleFinish = (values: T) => {
    onSubmit(values);
  };

  const handleReset = () => {
    if (onReset) {
      onReset();
      return;
    }

    if (initialValues) {
      form.setFieldsValue(initialValues);
    } else {
      form.resetFields();
    }

    const currentValues = form.getFieldsValue(true) as T;
    onSubmit(currentValues);
  };

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        const values = form.getFieldsValue(true) as T;
        handleFinish(values);
      }}
      style={{ width: '100%' }}
    >
      <div
        style={{
          width: '100%',
          display: 'flex',
          flexWrap: 'wrap',
          gap: 12,
          alignItems: 'stretch',
        }}
      >
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: 12,
            flex: 1,
            minWidth: 0,
          }}
        >
          {React.Children.map(children, (child) => (
            <div style={{ minWidth: 160, flex: '0 1 auto' }}>{child}</div>
          ))}
        </div>
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: 8,
            alignItems: 'center',
          }}
        >
          <button
            type="submit"
            disabled={loading}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              borderRadius: 999,
              paddingInline: 12,
              paddingBlock: 4,
              fontSize: 13,
              fontWeight: 600,
            }}
          >
            {submitLabel}
          </button>
          <button
            type="button"
            onClick={handleReset}
            disabled={loading}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              borderRadius: 999,
              paddingInline: 12,
              paddingBlock: 4,
              fontSize: 13,
              fontWeight: 500,
            }}
          >
            {resetLabel}
          </button>
        </div>
      </div>
    </form>
  );
}
