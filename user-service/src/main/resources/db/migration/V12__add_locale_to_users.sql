ALTER TABLE users
ADD COLUMN IF NOT EXISTS locale varchar(16);

UPDATE users
SET locale = 'tr'
WHERE locale IS NULL OR trim(locale) = '';

ALTER TABLE users
ALTER COLUMN locale SET DEFAULT 'tr';

ALTER TABLE users
ALTER COLUMN locale SET NOT NULL;
