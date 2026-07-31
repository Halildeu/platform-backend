-- Faz 22 Sec / gitops#3212 — SMS OTP (MFA) template for the Keycloak SMS
-- authenticator lane: KC SPI -> auth-service mint -> notify internal intent
-- -> NetGSM.
--
-- external_allowed=TRUE is deliberate and load-bearing: the KC SPI submits
-- recipients as {type: external, phone: E.164} (the login user is not a
-- notify subscriber), and DeliveryEligibilityService hard-blocks external
-- recipients when the template says FALSE.
--
-- Locales are language-only ('tr', 'en') on purpose. TemplateResolver's
-- fallback chain is requested -> language-only -> en-US -> en, so 'tr'
-- serves both "tr" and "tr-TR" requests; a 'tr-TR' row would NOT serve a
-- plain "tr" request (it would fall through to English).
--
-- Body is SMS-only (body_text; subject/body_html NULL). The tr body avoids
-- Turkish diacritics on purpose: ı/ş/ğ push the message out of the GSM-7
-- alphabet into UCS-2, turning a 1-segment OTP into 2 paid segments.
--
-- Payload contract: {code} — rendered via the vars namespace.

INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text,
     external_allowed, active, created_by)
SELECT
    'auth.sms-otp', 1, 'tr',
    NULL, NULL,
    'Tek kullanimlik giris kodunuz: [[${vars.code}]]. 5 dakika gecerlidir. Kodu kimseyle paylasmayin.',
    TRUE, TRUE, 'migration-faz22-sec-sms-otp'
WHERE NOT EXISTS (
    SELECT 1 FROM notify.notification_template
    WHERE template_id = 'auth.sms-otp' AND version = 1 AND locale = 'tr'
);

INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text,
     external_allowed, active, created_by)
SELECT
    'auth.sms-otp', 1, 'en',
    NULL, NULL,
    'Your one-time sign-in code: [[${vars.code}]]. Valid for 5 minutes. Do not share it.',
    TRUE, TRUE, 'migration-faz22-sec-sms-otp'
WHERE NOT EXISTS (
    SELECT 1 FROM notify.notification_template
    WHERE template_id = 'auth.sms-otp' AND version = 1 AND locale = 'en'
);
