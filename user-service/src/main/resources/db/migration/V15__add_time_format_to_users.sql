ALTER TABLE users
ADD COLUMN IF NOT EXISTS time_format VARCHAR(16);

UPDATE users
SET time_format = 'HH:mm'
WHERE time_format IS NULL
   OR btrim(time_format) = '';

ALTER TABLE users
ALTER COLUMN time_format SET DEFAULT 'HH:mm';

ALTER TABLE users
ALTER COLUMN time_format SET NOT NULL;
