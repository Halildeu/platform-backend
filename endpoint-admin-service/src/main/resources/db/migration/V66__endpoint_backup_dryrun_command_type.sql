-- Faz 22.8A (#117) — COLLECT_BACKUP_DRYRUN command type database guard.
--
-- Scope:
--   * Extends endpoint_commands.command_type CHECK with COLLECT_BACKUP_DRYRUN.
--   * Does NOT add a generic dispatch / issuing path. The agent capability is
--     disabled-by-default and the manifest is metadata-only; the result is
--     re-validated server-side by BackupDryRunManifestPayloadPolicy (contract
--     §5 mirror). Generic admin command creation stays fail-closed.
--
-- Follows the V53/V58 discover-and-replace pattern because the CHECK
-- constraint name can differ across historical clusters before the canonical
-- ck_endpoint_commands_type name was stabilized.

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
        'UPDATE_AGENT',
        'SET_DISPLAY_POLICY',
        'COLLECT_BACKUP_DRYRUN'
    ));

COMMENT ON CONSTRAINT ck_endpoint_commands_type ON endpoint_commands IS
    'Faz 22.8A #117: COLLECT_BACKUP_DRYRUN is the metadata-only backup dry-run manifest command; the result is re-validated by BackupDryRunManifestPayloadPolicy (contract §5 mirror). Disabled-by-default on the agent; generic admin command creation stays fail-closed. Preserves V58 SET_DISPLAY_POLICY + V53 UPDATE_AGENT.';
