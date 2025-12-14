insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000010',
    'overlay.bg',
    'Overlay background',
    'overlay',
    'COLOR',
    'USER_ALLOWED',
    'Overlay arka plan rengi.',
    'semantic.color.surface.overlay.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--surface-overlay-bg'
from theme_registry
where key = 'overlay.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000011',
    'text.primary',
    'Text primary',
    'text',
    'COLOR',
    'USER_ALLOWED',
    'Birincil metin rengi.',
    'semantic.color.text.primary'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--text-primary'
from theme_registry
where key = 'text.primary'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000012',
    'text.secondary',
    'Text secondary',
    'text',
    'COLOR',
    'USER_ALLOWED',
    'İkincil metin rengi.',
    'semantic.color.text.secondary'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--text-secondary'
from theme_registry
where key = 'text.secondary'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000013',
    'text.subtle',
    'Text subtle',
    'text',
    'COLOR',
    'USER_ALLOWED',
    'Daha düşük öncelikli metin rengi.',
    'semantic.color.text.subtle'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--text-subtle'
from theme_registry
where key = 'text.subtle'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000014',
    'border.subtle',
    'Border subtle',
    'border',
    'COLOR',
    'USER_ALLOWED',
    'Subtle border rengi.',
    'semantic.color.border.subtle'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--border-subtle'
from theme_registry
where key = 'border.subtle'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000015',
    'border.default',
    'Border default',
    'border',
    'COLOR',
    'USER_ALLOWED',
    'Default border rengi.',
    'semantic.color.border.default'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--border-default'
from theme_registry
where key = 'border.default'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000016',
    'accent.primary',
    'Accent primary',
    'accent',
    'COLOR',
    'USER_ALLOWED',
    'Birincil accent rengi.',
    'semantic.theme.accent.*.primary'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--accent-primary'
from theme_registry
where key = 'accent.primary'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000017',
    'accent.primary-hover',
    'Accent primary hover',
    'accent',
    'COLOR',
    'USER_ALLOWED',
    'Accent hover rengi.',
    'semantic.theme.accent.*.primary-hover'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--accent-primary-hover'
from theme_registry
where key = 'accent.primary-hover'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000018',
    'accent.focus',
    'Accent focus',
    'accent',
    'COLOR',
    'USER_ALLOWED',
    'Focus/outline rengi.',
    'semantic.theme.accent.*.focus'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--accent-focus'
from theme_registry
where key = 'accent.focus'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000019',
    'accent.soft',
    'Accent soft',
    'accent',
    'COLOR',
    'USER_ALLOWED',
    'Accent soft rengi.',
    'semantic.theme.accent.*.soft'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--accent-soft'
from theme_registry
where key = 'accent.soft'
on conflict do nothing;
