insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000020',
    'surface.raised.bg',
    'Surface raised background',
    'surface',
    'COLOR',
    'USER_ALLOWED',
    'Raised yüzey arka plan rengi.',
    'semantic.color.surface.raised.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--surface-raised-bg'
from theme_registry
where key = 'surface.raised.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000021',
    'surface.muted.bg',
    'Surface muted background',
    'surface',
    'COLOR',
    'USER_ALLOWED',
    'Muted yüzey arka plan rengi.',
    'semantic.color.surface.muted.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--surface-muted-bg'
from theme_registry
where key = 'surface.muted.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000022',
    'surface.panel.bg',
    'Surface panel background',
    'surface',
    'COLOR',
    'USER_ALLOWED',
    'Panel yüzey arka plan rengi.',
    'semantic.color.surface.panel.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--surface-panel-bg'
from theme_registry
where key = 'surface.panel.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000023',
    'surface.header.bg',
    'Surface header background',
    'surface',
    'COLOR',
    'USER_ALLOWED',
    'Header yüzey arka plan rengi.',
    'semantic.color.surface.header.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--surface-header-bg'
from theme_registry
where key = 'surface.header.bg'
on conflict do nothing;
