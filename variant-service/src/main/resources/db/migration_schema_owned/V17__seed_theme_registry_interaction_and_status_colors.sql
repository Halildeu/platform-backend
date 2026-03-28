CREATE SCHEMA IF NOT EXISTS variant_service;
SET search_path TO variant_service, public;

-- Adds missing design-tokens so Theme Admin can control all text/background/action/status colors.

-- TEXT (inverse)
insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000033',
    'text.inverse',
    'Text inverse',
    'text',
    'COLOR',
    'USER_ALLOWED',
    'Overlay vb. alanlarda kullanılan ters metin rengi.',
    'semantic.color.text.inverse'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--text-inverse'
from theme_registry
where key = 'text.inverse'
on conflict do nothing;

-- BORDER (bold)
insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000034',
    'border.bold',
    'Border bold',
    'border',
    'COLOR',
    'USER_ALLOWED',
    'Vurgu/selected border rengi.',
    'semantic.color.border.bold'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--border-bold'
from theme_registry
where key = 'border.bold'
on conflict do nothing;

-- SELECTION + FOCUS (interactive hints)
insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000035',
    'selection.bg',
    'Selection background',
    'selection',
    'COLOR',
    'USER_ALLOWED',
    'Seçili alan arka plan rengi.',
    'semantic.color.selection.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--selection-bg'
from theme_registry
where key = 'selection.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000036',
    'selection.outline',
    'Selection outline',
    'selection',
    'COLOR',
    'USER_ALLOWED',
    'Seçim/odak outline rengi (focus ring vb.).',
    'semantic.color.selection.outline'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--selection-outline'
from theme_registry
where key = 'selection.outline'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000037',
    'focus.outline',
    'Focus outline',
    'selection',
    'COLOR',
    'USER_ALLOWED',
    'Varsayılan focus outline rengi.',
    'semantic.color.focus.outline'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--focus-outline'
from theme_registry
where key = 'focus.outline'
on conflict do nothing;

-- ACTION (buttons etc.) - ADMIN only
insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000038',
    'action.primary.bg',
    'Action primary background',
    'erpAction',
    'COLOR',
    'ADMIN_ONLY',
    'Primary action buton arka plan rengi.',
    'semantic.color.action.primary.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--action-primary-bg'
from theme_registry
where key = 'action.primary.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000039',
    'action.primary.text',
    'Action primary text',
    'erpAction',
    'COLOR',
    'ADMIN_ONLY',
    'Primary action buton metin rengi.',
    'semantic.color.action.primary.text'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--action-primary-text'
from theme_registry
where key = 'action.primary.text'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000040',
    'action.primary.border',
    'Action primary border',
    'erpAction',
    'COLOR',
    'ADMIN_ONLY',
    'Primary action buton border rengi.',
    'semantic.color.action.primary.border'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--action-primary-border'
from theme_registry
where key = 'action.primary.border'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000041',
    'action.secondary.bg',
    'Action secondary background',
    'erpAction',
    'COLOR',
    'ADMIN_ONLY',
    'Secondary action arka plan rengi.',
    'semantic.color.action.secondary.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--action-secondary-bg'
from theme_registry
where key = 'action.secondary.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000042',
    'action.secondary.text',
    'Action secondary text',
    'erpAction',
    'COLOR',
    'ADMIN_ONLY',
    'Secondary action metin rengi.',
    'semantic.color.action.secondary.text'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--action-secondary-text'
from theme_registry
where key = 'action.secondary.text'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000043',
    'action.secondary.border',
    'Action secondary border',
    'erpAction',
    'COLOR',
    'ADMIN_ONLY',
    'Secondary action border rengi.',
    'semantic.color.action.secondary.border'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--action-secondary-border'
from theme_registry
where key = 'action.secondary.border'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000044',
    'action.ghost.bg',
    'Action ghost background',
    'erpAction',
    'COLOR',
    'ADMIN_ONLY',
    'Ghost action arka plan rengi.',
    'semantic.color.action.ghost.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--action-ghost-bg'
from theme_registry
where key = 'action.ghost.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000045',
    'action.ghost.text',
    'Action ghost text',
    'erpAction',
    'COLOR',
    'ADMIN_ONLY',
    'Ghost action metin rengi.',
    'semantic.color.action.ghost.text'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--action-ghost-text'
from theme_registry
where key = 'action.ghost.text'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000046',
    'action.ghost.border',
    'Action ghost border',
    'erpAction',
    'COLOR',
    'ADMIN_ONLY',
    'Ghost action border rengi.',
    'semantic.color.action.ghost.border'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--action-ghost-border'
from theme_registry
where key = 'action.ghost.border'
on conflict do nothing;

-- STATUS (state) - ADMIN only (Tailwind: state.*, but UI contract uses status.*)
insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000047',
    'status.info.bg',
    'Status info background',
    'status',
    'COLOR',
    'ADMIN_ONLY',
    'Bilgi mesajı arka plan rengi.',
    'semantic.color.state.info.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--state-info-bg'
from theme_registry
where key = 'status.info.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000048',
    'status.info.text',
    'Status info text',
    'status',
    'COLOR',
    'ADMIN_ONLY',
    'Bilgi mesajı metin rengi.',
    'semantic.color.state.info.text'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--state-info-text'
from theme_registry
where key = 'status.info.text'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000049',
    'status.info.border',
    'Status info border',
    'status',
    'COLOR',
    'ADMIN_ONLY',
    'Bilgi mesajı border rengi.',
    'semantic.color.state.info.border'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--state-info-border'
from theme_registry
where key = 'status.info.border'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000050',
    'status.success.bg',
    'Status success background',
    'status',
    'COLOR',
    'ADMIN_ONLY',
    'Başarı mesajı arka plan rengi.',
    'semantic.color.state.success.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--state-success-bg'
from theme_registry
where key = 'status.success.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000051',
    'status.success.text',
    'Status success text',
    'status',
    'COLOR',
    'ADMIN_ONLY',
    'Başarı mesajı metin rengi.',
    'semantic.color.state.success.text'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--state-success-text'
from theme_registry
where key = 'status.success.text'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000052',
    'status.success.border',
    'Status success border',
    'status',
    'COLOR',
    'ADMIN_ONLY',
    'Başarı mesajı border rengi.',
    'semantic.color.state.success.border'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--state-success-border'
from theme_registry
where key = 'status.success.border'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000053',
    'status.warning.bg',
    'Status warning background',
    'status',
    'COLOR',
    'ADMIN_ONLY',
    'Uyarı mesajı arka plan rengi.',
    'semantic.color.state.warning.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--state-warning-bg'
from theme_registry
where key = 'status.warning.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000054',
    'status.warning.text',
    'Status warning text',
    'status',
    'COLOR',
    'ADMIN_ONLY',
    'Uyarı mesajı metin rengi.',
    'semantic.color.state.warning.text'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--state-warning-text'
from theme_registry
where key = 'status.warning.text'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000055',
    'status.warning.border',
    'Status warning border',
    'status',
    'COLOR',
    'ADMIN_ONLY',
    'Uyarı mesajı border rengi.',
    'semantic.color.state.warning.border'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--state-warning-border'
from theme_registry
where key = 'status.warning.border'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000056',
    'status.danger.bg',
    'Status danger background',
    'status',
    'COLOR',
    'ADMIN_ONLY',
    'Hata mesajı arka plan rengi.',
    'semantic.color.state.danger.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--state-danger-bg'
from theme_registry
where key = 'status.danger.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000057',
    'status.danger.text',
    'Status danger text',
    'status',
    'COLOR',
    'ADMIN_ONLY',
    'Hata mesajı metin rengi.',
    'semantic.color.state.danger.text'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--state-danger-text'
from theme_registry
where key = 'status.danger.text'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000058',
    'status.danger.border',
    'Status danger border',
    'status',
    'COLOR',
    'ADMIN_ONLY',
    'Hata mesajı border rengi.',
    'semantic.color.state.danger.border'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--state-danger-border'
from theme_registry
where key = 'status.danger.border'
on conflict do nothing;

