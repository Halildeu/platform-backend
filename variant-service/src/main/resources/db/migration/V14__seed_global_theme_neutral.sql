insert into themes (id, name, type, appearance, surface_tone, density, radius, elevation, motion, is_global, accent)
values (
    '00000000-0000-0000-0000-000000000108',
    'Global Neutral',
    'GLOBAL',
    'light',
    'ultra-1',
    'comfortable',
    'rounded',
    'raised',
    'standard',
    true,
    'neutral'
)
on conflict (id) do nothing;

