CREATE SCHEMA IF NOT EXISTS variant_service;
SET search_path TO variant_service, public;

update themes
set accent = 'light'
where id = '00000000-0000-0000-0000-000000000101';

insert into themes (id, name, type, appearance, surface_tone, density, radius, elevation, motion, is_global, accent)
values (
    '00000000-0000-0000-0000-000000000103',
    'Global Violet',
    'GLOBAL',
    'light',
    'ultra-1',
    'comfortable',
    'rounded',
    'raised',
    'standard',
    true,
    'violet'
)
on conflict (id) do nothing;

insert into themes (id, name, type, appearance, surface_tone, density, radius, elevation, motion, is_global, accent)
values (
    '00000000-0000-0000-0000-000000000104',
    'Global Emerald',
    'GLOBAL',
    'light',
    'ultra-1',
    'comfortable',
    'rounded',
    'raised',
    'standard',
    true,
    'emerald'
)
on conflict (id) do nothing;

insert into themes (id, name, type, appearance, surface_tone, density, radius, elevation, motion, is_global, accent)
values (
    '00000000-0000-0000-0000-000000000105',
    'Global Sunset',
    'GLOBAL',
    'light',
    'ultra-1',
    'comfortable',
    'rounded',
    'raised',
    'standard',
    true,
    'sunset'
)
on conflict (id) do nothing;

insert into themes (id, name, type, appearance, surface_tone, density, radius, elevation, motion, is_global, accent)
values (
    '00000000-0000-0000-0000-000000000106',
    'Global Ocean',
    'GLOBAL',
    'light',
    'ultra-1',
    'comfortable',
    'rounded',
    'raised',
    'standard',
    true,
    'ocean'
)
on conflict (id) do nothing;

insert into themes (id, name, type, appearance, surface_tone, density, radius, elevation, motion, is_global, accent)
values (
    '00000000-0000-0000-0000-000000000107',
    'Global Graphite',
    'GLOBAL',
    'light',
    'ultra-1',
    'comfortable',
    'rounded',
    'raised',
    'standard',
    true,
    'graphite'
)
on conflict (id) do nothing;

