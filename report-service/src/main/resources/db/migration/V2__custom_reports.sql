-- Custom reports, dashboards, alerts, and schedules persistence
-- These tables live in PostgreSQL while report data is queried from MSSQL

CREATE TABLE IF NOT EXISTS custom_reports (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  key VARCHAR(128) UNIQUE NOT NULL,
  title VARCHAR(256) NOT NULL,
  description TEXT,
  category VARCHAR(128) DEFAULT 'Özel',
  source_schema VARCHAR(128) NOT NULL,
  source_table VARCHAR(256) NOT NULL,
  columns JSONB NOT NULL,
  default_sort VARCHAR(128),
  default_sort_direction VARCHAR(4) DEFAULT 'asc',
  access_config JSONB,
  tags TEXT[],
  version INT DEFAULT 1,
  deleted BOOLEAN DEFAULT false,
  created_by VARCHAR(256) NOT NULL,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS custom_report_versions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  report_id UUID NOT NULL REFERENCES custom_reports(id) ON DELETE CASCADE,
  version INT NOT NULL,
  columns JSONB NOT NULL,
  source_table VARCHAR(256) NOT NULL,
  changed_by VARCHAR(256) NOT NULL,
  changed_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS custom_dashboards (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  key VARCHAR(128) UNIQUE NOT NULL,
  title VARCHAR(256) NOT NULL,
  description TEXT,
  widgets JSONB NOT NULL,
  layout JSONB,
  created_by VARCHAR(256) NOT NULL,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS alert_rules (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  report_key VARCHAR(128) NOT NULL,
  field VARCHAR(128) NOT NULL,
  condition VARCHAR(32) NOT NULL,
  threshold NUMERIC,
  channels TEXT[] NOT NULL,
  frequency VARCHAR(32) DEFAULT 'daily',
  enabled BOOLEAN DEFAULT true,
  created_by VARCHAR(256) NOT NULL,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS report_schedules (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  report_key VARCHAR(128) NOT NULL,
  cron VARCHAR(64) NOT NULL,
  timezone VARCHAR(64) DEFAULT 'Europe/Istanbul',
  recipients TEXT[] NOT NULL,
  format VARCHAR(16) DEFAULT 'excel',
  enabled BOOLEAN DEFAULT true,
  created_by VARCHAR(256) NOT NULL,
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_custom_reports_key ON custom_reports(key) WHERE NOT deleted;
CREATE INDEX IF NOT EXISTS idx_custom_reports_category ON custom_reports(category) WHERE NOT deleted;
CREATE INDEX IF NOT EXISTS idx_report_versions_report ON custom_report_versions(report_id);
CREATE INDEX IF NOT EXISTS idx_alert_rules_report ON alert_rules(report_key);
CREATE INDEX IF NOT EXISTS idx_report_schedules_report ON report_schedules(report_key);
