-- Faz 22.6 #2913 — browser-managed TPM/client-certificate renewal.
--
-- The command type is recognized by PostgreSQL, but remains dedicated-path
-- only in EndpointAdminCommandService. Its enrollment token is encrypted in
-- endpoint_command_secrets and never stored in endpoint_commands.payload.

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
        'RENEW_TPM_CERTIFICATE',
        'SET_DISPLAY_POLICY',
        'COLLECT_BACKUP_DRYRUN'
    ));

COMMENT ON CONSTRAINT ck_endpoint_commands_type ON endpoint_commands IS
    'Faz 22.6 #2913: RENEW_TPM_CERTIFICATE is dedicated-path-only and uses encrypted one-use command-secret delivery. Preserves UPDATE_AGENT, SET_DISPLAY_POLICY and COLLECT_BACKUP_DRYRUN.';
