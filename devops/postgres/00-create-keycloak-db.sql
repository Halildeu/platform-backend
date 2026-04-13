-- Create dedicated Keycloak database and user
-- Runs on first postgres-db initialization only (initdb.d)
SELECT 'CREATE DATABASE keycloak' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak')\gexec
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'keycloak_user') THEN
    CREATE ROLE keycloak_user WITH LOGIN PASSWORD 'keycloak';
  END IF;
END $$;
GRANT ALL PRIVILEGES ON DATABASE keycloak TO keycloak_user;
