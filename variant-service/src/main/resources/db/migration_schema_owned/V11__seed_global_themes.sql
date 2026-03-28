CREATE SCHEMA IF NOT EXISTS variant_service;
SET search_path TO variant_service, public;

insert into themes (id, name, type, appearance, surface_tone, density, radius, elevation, motion, is_global)
values (
    '00000000-0000-0000-0000-000000000101',
    'Global Light',
    'GLOBAL',
    'light',
    'ultra-1',
    'comfortable',
    'rounded',
    'raised',
    'standard',
    true
)
on conflict (id) do nothing;

insert into themes (id, name, type, appearance, surface_tone, density, radius, elevation, motion, is_global)
values (
    '00000000-0000-0000-0000-000000000102',
    'Global Dark',
    'GLOBAL',
    'dark',
    'deep-6',
    'comfortable',
    'rounded',
    'raised',
    'standard',
    true
)
on conflict (id) do nothing;

