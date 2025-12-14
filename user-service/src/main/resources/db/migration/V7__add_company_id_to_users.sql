-- SCOPE-03 (user-service): company scope kancası için company_id kolonu
-- İlk fazda nullable bırakılıyor; backfill/NOT NULL ileride değerlendirilecek.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS company_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_users_company_id ON users(company_id);
