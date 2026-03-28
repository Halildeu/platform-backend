CREATE SCHEMA IF NOT EXISTS core_data_service;
SET search_path TO core_data_service, public;

CREATE TABLE IF NOT EXISTS companies (
    id BIGSERIAL PRIMARY KEY,
    company_code VARCHAR(64) NOT NULL UNIQUE,
    company_name VARCHAR(255) NOT NULL,
    short_name VARCHAR(128),
    company_type VARCHAR(32),
    tax_number VARCHAR(32),
    tax_office VARCHAR(128),
    country_code VARCHAR(2),
    city VARCHAR(128),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW() NOT NULL,
    created_by VARCHAR(64),
    updated_at TIMESTAMP WITH TIME ZONE,
    updated_by VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_companies_code ON companies (company_code);
CREATE INDEX IF NOT EXISTS idx_companies_status ON companies (status);
