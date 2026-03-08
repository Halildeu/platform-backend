CREATE SCHEMA IF NOT EXISTS variant_service;
SET search_path TO variant_service, public;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000059',
    'surface.page.bg',
    'Surface page background',
    'surface',
    'COLOR',
    'USER_ALLOWED',
    'Sayfa (body) arka plan rengi. Varsayılan olarak surface.default.bg ile aynıdır.',
    'semantic.color.surface.default.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--surface-page-bg'
from theme_registry
where key = 'surface.page.bg'
on conflict do nothing;

