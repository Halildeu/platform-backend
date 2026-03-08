CREATE SCHEMA IF NOT EXISTS variant_service;
SET search_path TO variant_service, public;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000001',
    'surface.default.bg',
    'Surface default background',
    'surface',
    'COLOR',
    'USER_ALLOWED',
    'Kullanıcı tarafından değiştirilebilir yüzey arka plan rengi.',
    'semantic.color.surface.default.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--surface-default-bg'
from theme_registry
where key = 'surface.default.bg'
on conflict do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--surface-raised-bg'
from theme_registry
where key = 'surface.default.bg'
on conflict do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--surface-muted-bg'
from theme_registry
where key = 'surface.default.bg'
on conflict do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--surface-panel-bg'
from theme_registry
where key = 'surface.default.bg'
on conflict do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--surface-header-bg'
from theme_registry
where key = 'surface.default.bg'
on conflict do nothing;

