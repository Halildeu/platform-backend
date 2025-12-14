insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000024',
    'grid.header.bg',
    'Grid header background',
    'grid',
    'COLOR',
    'ADMIN_ONLY',
    'Grid header arka plan rengi.',
    'semantic.color.dataTable.header.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--data-table-header-bg'
from theme_registry
where key = 'grid.header.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000025',
    'grid.header.text',
    'Grid header text',
    'grid',
    'COLOR',
    'ADMIN_ONLY',
    'Grid header metin rengi.',
    'semantic.color.dataTable.header.text'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--data-table-header-text'
from theme_registry
where key = 'grid.header.text'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000026',
    'grid.header.divider',
    'Grid header divider',
    'grid',
    'COLOR',
    'ADMIN_ONLY',
    'Grid header ayırıcı çizgi rengi.',
    'semantic.color.dataTable.header.divider'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--data-table-header-divider'
from theme_registry
where key = 'grid.header.divider'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000027',
    'grid.row.hover',
    'Grid row hover',
    'grid',
    'COLOR',
    'ADMIN_ONLY',
    'Grid satır hover rengi.',
    'semantic.color.dataTable.row.hover'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--data-table-row-hover'
from theme_registry
where key = 'grid.row.hover'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000028',
    'grid.row.selected',
    'Grid row selected',
    'grid',
    'COLOR',
    'ADMIN_ONLY',
    'Grid seçili satır rengi.',
    'semantic.color.dataTable.row.selected'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--data-table-row-selected'
from theme_registry
where key = 'grid.row.selected'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000029',
    'grid.row.border',
    'Grid row border',
    'grid',
    'COLOR',
    'ADMIN_ONLY',
    'Grid satır border rengi.',
    'semantic.color.dataTable.row.border'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--data-table-row-border'
from theme_registry
where key = 'grid.row.border'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000030',
    'grid.table.soft.bg',
    'Grid table soft background',
    'grid',
    'COLOR',
    'ADMIN_ONLY',
    'Grid soft table yüzeyi arka plan rengi.',
    'semantic.color.dataTable.surface.soft.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--surface-table-soft-bg'
from theme_registry
where key = 'grid.table.soft.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000031',
    'grid.table.normal.bg',
    'Grid table normal background',
    'grid',
    'COLOR',
    'ADMIN_ONLY',
    'Grid normal table yüzeyi arka plan rengi.',
    'semantic.color.dataTable.surface.normal.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--surface-table-normal-bg'
from theme_registry
where key = 'grid.table.normal.bg'
on conflict do nothing;

insert into theme_registry (id, key, label, group_name, control_type, editable_by, description, default_source)
values (
    '00000000-0000-0000-0000-000000000032',
    'grid.table.strong.bg',
    'Grid table strong background',
    'grid',
    'COLOR',
    'ADMIN_ONLY',
    'Grid strong table yüzeyi arka plan rengi.',
    'semantic.color.dataTable.surface.strong.bg'
)
on conflict (key) do nothing;

insert into theme_registry_css_vars (registry_id, css_var)
select id, '--surface-table-strong-bg'
from theme_registry
where key = 'grid.table.strong.bg'
on conflict do nothing;
