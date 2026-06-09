-- Faz 22.5 AG-043: active (interactive console) user reported by the agent
-- (Win32_ComputerSystem.UserName), shown in the endpoint-admin grid + drawer.
-- Personal data (KVKK m.5/1) under the device-management lawful basis; redacted
-- in logs (governance: ADR-0012-EA DC-EA device-identity note).
ALTER TABLE endpoint_devices ADD COLUMN IF NOT EXISTS active_user VARCHAR(255);
