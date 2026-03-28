ALTER TABLE users
    ADD COLUMN IF NOT EXISTS date_format VARCHAR(32);

UPDATE users
SET date_format = 'dd.MM.yyyy'
WHERE date_format IS NULL
   OR trim(date_format) = '';

ALTER TABLE users
    ALTER COLUMN date_format SET DEFAULT 'dd.MM.yyyy';

ALTER TABLE users
    ALTER COLUMN date_format SET NOT NULL;
