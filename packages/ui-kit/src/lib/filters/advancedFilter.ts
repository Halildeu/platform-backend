export type AdvancedFieldType = 'string' | 'number' | 'datetime' | 'boolean';

export type AdvancedFilterOperator =
  | 'equals'
  | 'notEqual'
  | 'contains'
  | 'notContains'
  | 'lessThan'
  | 'greaterThan'
  | 'inRange';

export type AdvancedFilterField = {
  field: string;
  label?: string;
  type: AdvancedFieldType;
  // ops sınırı verilmezse tipten türetilen varsayılan set kullanılır
  operators?: AdvancedFilterOperator[];
};

export type AdvancedFilterSchema = {
  fields: AdvancedFilterField[];
};

export type AdvancedFilterCondition = {
  field: string;
  op: AdvancedFilterOperator;
  value?: string | number | boolean;
  value2?: string | number | boolean; // inRange için
};

export type AdvancedFilterModel = {
  logic: 'and' | 'or';
  conditions: AdvancedFilterCondition[];
};

// Tip → operatör eşlemesi (varsayılan)
const DEFAULT_OPS: Record<AdvancedFieldType, AdvancedFilterOperator[]> = {
  string: ['equals', 'notEqual', 'contains', 'notContains'],
  number: ['equals', 'notEqual', 'lessThan', 'greaterThan', 'inRange'],
  datetime: ['equals', 'notEqual', 'lessThan', 'greaterThan', 'inRange'],
  boolean: ['equals', 'notEqual'],
};

export function allowedOperatorsForField(
  schema: AdvancedFilterSchema,
  fieldName: string
): AdvancedFilterOperator[] {
  const f = schema.fields.find((x) => x.field === fieldName);
  if (!f) return [];
  return f.operators && f.operators.length > 0 ? f.operators : DEFAULT_OPS[f.type];
}

export function validateAdvancedFilter(
  schema: AdvancedFilterSchema,
  model: AdvancedFilterModel
): { ok: true } | { ok: false; message: string } {
  if (!model || !Array.isArray(model.conditions)) {
    return { ok: false, message: 'invalid_advanced_filter' };
  }
  for (const c of model.conditions) {
    const f = schema.fields.find((x) => x.field === c.field);
    if (!f) return { ok: false, message: 'invalid_advanced_filter_field' };
    const ops = allowedOperatorsForField(schema, f.field);
    if (!ops.includes(c.op)) return { ok: false, message: 'invalid_advanced_filter_operator' };
    if (c.op === 'inRange' && (c.value === undefined || c.value2 === undefined)) {
      return { ok: false, message: 'invalid_advanced_filter_inrange' };
    }
  }
  return { ok: true };
}

export function buildAdvancedFilterParam(model: AdvancedFilterModel): string {
  const json = JSON.stringify(model);
  return encodeURIComponent(json);
}

// Backend ile hizalı örnek şema (Users)
export const defaultUsersAdvancedFilterSchema: AdvancedFilterSchema = {
  fields: [
    { field: 'name', label: 'Ad Soyad', type: 'string' },
    { field: 'email', label: 'E-Posta', type: 'string' },
    { field: 'role', label: 'Rol', type: 'string' },
    { field: 'enabled', label: 'Aktif', type: 'boolean' },
    { field: 'createDate', label: 'Oluşturulma', type: 'datetime' },
    { field: 'lastLogin', label: 'Son Giriş', type: 'datetime' },
    { field: 'sessionTimeoutMinutes', label: 'Oturum Süresi (dk)', type: 'number' },
  ],
};

