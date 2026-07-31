-- Faz 22 Sec / gitops#3230 — e-mail OTP (MFA) template, the third second
-- factor alongside the authenticator app and SMS. Same lane as V25's SMS
-- template: KC SPI -> auth-service mint + delivery grant -> notify internal
-- intent -> SMTP/Graph.
--
-- external_allowed=TRUE for the same reason it is set on auth.sms-otp: the
-- SPI submits {type: external, email: ...} because the account logging in is
-- not a notify subscriber, and DeliveryEligibilityService hard-blocks
-- external recipients when the template says FALSE.
--
-- Locales are language-only ('tr', 'en'). TemplateResolver falls back
-- requested -> language-only -> en-US -> en, so 'tr' serves both "tr" and
-- "tr-TR"; a 'tr-TR' row would NOT serve a plain "tr" request.
--
-- Unlike the SMS body this one carries a subject and uses Turkish diacritics
-- freely: the GSM-7 segment arithmetic that forces ASCII on SMS has no
-- equivalent here.
--
-- Payload contract: {code}, rendered through the Thymeleaf vars namespace.
-- The dollar sign is concatenated in, and NOWHERE in this file — comments
-- included — may a dollar sign sit directly before an opening brace: Flyway
-- scans the raw migration text (comments too) for its own placeholder syntax
-- and fails the whole script on sight. Script-scoped
-- placeholderReplacement=false is not honoured by OSS Flyway (measured on
-- V25; see that file's note).

INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text,
     external_allowed, active, created_by)
SELECT
    'auth.email-otp', 1, 'tr',
    'Giriş doğrulama kodunuz',
    NULL,
    'Tek kullanımlık giriş kodunuz: [[' || '$' || '{vars.code}]]' || chr(10) || chr(10)
        || 'Kod 5 dakika geçerlidir ve yalnız bir kez kullanılabilir.' || chr(10)
        || 'Bu girişi siz başlatmadıysanız kodu kimseyle paylaşmayın ve parolanızı değiştirin.',
    TRUE, TRUE, 'migration-faz22-sec-email-otp'
WHERE NOT EXISTS (
    SELECT 1 FROM notify.notification_template
    WHERE template_id = 'auth.email-otp' AND version = 1 AND locale = 'tr'
);

INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text,
     external_allowed, active, created_by)
SELECT
    'auth.email-otp', 1, 'en',
    'Your sign-in verification code',
    NULL,
    'Your one-time sign-in code: [[' || '$' || '{vars.code}]]' || chr(10) || chr(10)
        || 'It is valid for 5 minutes and can be used once.' || chr(10)
        || 'If you did not start this sign-in, do not share the code with anyone and change your password.',
    TRUE, TRUE, 'migration-faz22-sec-email-otp'
WHERE NOT EXISTS (
    SELECT 1 FROM notify.notification_template
    WHERE template_id = 'auth.email-otp' AND version = 1 AND locale = 'en'
);
