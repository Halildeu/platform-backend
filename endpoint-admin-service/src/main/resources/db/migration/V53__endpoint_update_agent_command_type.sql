-- BE-030 / AG-029 — UPDATE_AGENT command type database guard.
--
-- Scope:
--   * Extends endpoint_commands.command_type CHECK with UPDATE_AGENT.
--   * Does NOT add a generic dispatch path.
--   * Does NOT add release catalog, trust metadata, signed manifest, or
--     rollout execution. Those stay in the dedicated self-update surface.
--
-- This migration intentionally follows V12/V32's discover-and-replace pattern
-- because the CHECK constraint name can differ across historical clusters
-- before the canonical ck_endpoint_commands_type name was stabilized.

DO $$
DECLARE
    cn text;
BEGIN
    SELECT conname INTO cn
    FROM pg_constraint
    WHERE conrelid = 'endpoint_commands'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%command_type%';

    IF cn IS NOT NULL THEN
        EXECUTE 'ALTER TABLE endpoint_commands DROP CONSTRAINT ' || quote_ident(cn);
    END IF;
END $$;

ALTER TABLE endpoint_commands ADD CONSTRAINT ck_endpoint_commands_type
    CHECK (command_type IN (
        'COLLECT_INVENTORY',
        'LOCK_USER_LOGIN',
        'UNLOCK_USER_LOGIN',
        'CHANGE_LOCAL_PASSWORD',
        'SMB_LIST_ALLOWED_PATH',
        'SMB_READ_FILE_METADATA',
        'SMB_DOWNLOAD_FILE',
        'SMB_UPLOAD_FILE',
        'ROTATE_CREDENTIAL',
        'INSTALL_SOFTWARE',
        'UNINSTALL_SOFTWARE',
        'UPDATE_AGENT'
    ));

COMMENT ON CONSTRAINT ck_endpoint_commands_type ON endpoint_commands IS
    'Faz 22.5 BE-030: UPDATE_AGENT is a recognized command type only for the dedicated signed self-update surface; generic admin command creation remains fail-closed in EndpointAdminCommandService.';
