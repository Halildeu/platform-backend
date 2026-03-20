ALTER TABLE users
    ADD COLUMN IF NOT EXISTS timezone varchar(64);

UPDATE users
SET timezone = 'Europe/Istanbul'
WHERE timezone IS NULL OR btrim(timezone) = '';

ALTER TABLE users
    ALTER COLUMN timezone SET DEFAULT 'Europe/Istanbul';

ALTER TABLE users
    ALTER COLUMN timezone SET NOT NULL;
