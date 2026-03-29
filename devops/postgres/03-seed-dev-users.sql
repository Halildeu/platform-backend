-- Dev/local mode seed users.
-- These users match the emails used by SecurityConfigLocal / LocalDevAnonymousAuthFilter.
-- Only needed when running with local/dev profile in Docker.

INSERT INTO user_service.users (name, email, password, role, enabled, company_id, create_date, session_timeout_minutes, locale, timezone, date_format, time_format, version)
VALUES
  ('Dev Admin', 'admin@serban.dev', '$2a$10$dummy_bcrypt_hash_for_dev_only', 'ADMIN', true, 1, NOW(), 60, 'tr', 'Europe/Istanbul', 'dd.MM.yyyy', 'HH:mm', 0),
  ('Admin User', 'admin@example.com', '$2a$10$dummy_bcrypt_hash_for_dev_only', 'ADMIN', true, 1, NOW(), 60, 'tr', 'Europe/Istanbul', 'dd.MM.yyyy', 'HH:mm', 0)
ON CONFLICT (email) DO NOTHING;
