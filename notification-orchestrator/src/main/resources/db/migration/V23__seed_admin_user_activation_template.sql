-- #734 (Codex 019ef41c) — admin "new M365 user awaiting activation" email template.
--
-- Seeds the `auth.admin-invite` template used by the internal system-submit
-- path (user-service -> POST /api/v1/internal/notify/intents) when a new M365
-- user first auto-provisions (passive, enabled=false) and needs an admin to
-- activate them.
--
-- external_allowed = TRUE: the recipient is the admin's external mailbox
-- (e.g. halil.kocoglu@serban.com.tr), delivered as a RecipientRef of
-- type=external. DeliveryEligibilityService blocks external recipients when
-- this flag is false, so it MUST be true for an admin-email use case.
--
-- TemplateRenderer (Thymeleaf) evaluates the body via the `vars` namespace at
-- send time, so the bodies must contain LITERAL Thymeleaf expressions like
-- {vars.new_user_email}. To stop Flyway's placeholder reader from resolving a
-- literal dollar-brace token, the dollar and brace are SQL-concatenated
-- ('$' || '{vars.x}') — the dollar-brace pair only exists in the stored value,
-- never in the raw migration text Flyway parses. Payload keys (snake_case):
-- new_user_email, new_user_name, user_id.
--
-- Idempotent via INSERT ... SELECT ... WHERE NOT EXISTS (a re-apply is a no-op).
-- NOTE: notification_template has an ON UPDATE DO INSTEAD NOTHING rule, which
-- makes Postgres reject INSERT ... ON CONFLICT, so the guard is WHERE NOT EXISTS.

INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text, external_allowed, active, created_by)
SELECT
    'auth.admin-invite', 1, 'tr-TR',
    'Yeni kullanıcı aktivasyon bekliyor: [[$' || '{vars.new_user_email}]]',
    '<p>Yeni bir kullanıcı Microsoft 365 ile ilk girişini yaptı ve <strong>aktivasyon bekliyor</strong>.</p>'
        || '<ul>'
        || '<li>Ad: [[$' || '{vars.new_user_name}]]</li>'
        || '<li>E-posta: [[$' || '{vars.new_user_email}]]</li>'
        || '<li>Kullanıcı No: [[$' || '{vars.user_id}]]</li>'
        || '</ul>'
        || '<p>Kullanıcı Yönetimi ekranından hesabı aktif edebilirsiniz.</p>',
    'Yeni bir kullanıcı Microsoft 365 ile ilk girişini yaptı ve aktivasyon bekliyor.' || chr(10)
        || 'Ad: [[$' || '{vars.new_user_name}]]' || chr(10)
        || 'E-posta: [[$' || '{vars.new_user_email}]]' || chr(10)
        || 'Kullanıcı No: [[$' || '{vars.user_id}]]' || chr(10)
        || 'Kullanıcı Yönetimi ekranindan hesabi aktif edebilirsiniz.',
    TRUE, TRUE, 'migration-734'
WHERE NOT EXISTS (
    SELECT 1 FROM notify.notification_template
    WHERE template_id = 'auth.admin-invite' AND version = 1 AND locale = 'tr-TR'
);

INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text, external_allowed, active, created_by)
SELECT
    'auth.admin-invite', 1, 'en-US',
    'New user awaiting activation: [[$' || '{vars.new_user_email}]]',
    '<p>A new user signed in with Microsoft 365 for the first time and is <strong>awaiting activation</strong>.</p>'
        || '<ul>'
        || '<li>Name: [[$' || '{vars.new_user_name}]]</li>'
        || '<li>Email: [[$' || '{vars.new_user_email}]]</li>'
        || '<li>User ID: [[$' || '{vars.user_id}]]</li>'
        || '</ul>'
        || '<p>You can activate the account from the User Management screen.</p>',
    'A new user signed in with Microsoft 365 for the first time and is awaiting activation.' || chr(10)
        || 'Name: [[$' || '{vars.new_user_name}]]' || chr(10)
        || 'Email: [[$' || '{vars.new_user_email}]]' || chr(10)
        || 'User ID: [[$' || '{vars.user_id}]]' || chr(10)
        || 'You can activate the account from the User Management screen.',
    TRUE, TRUE, 'migration-734'
WHERE NOT EXISTS (
    SELECT 1 FROM notify.notification_template
    WHERE template_id = 'auth.admin-invite' AND version = 1 AND locale = 'en-US'
);
