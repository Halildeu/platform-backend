-- Fixed copy only: no transcript, summary, title or identity in notifications.
INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text, external_allowed, active, created_by)
SELECT topic, 1, locale, label, '<p>' || label || '</p>', label, FALSE, TRUE, 'mobile-native-ready'
FROM (VALUES
    ('meeting.summary.ready', 'tr-TR', 'Toplantı özeti hazır'),
    ('meeting.transcript.ready', 'tr-TR', 'Toplantı metni hazır'),
    ('meeting.summary.ready', 'en-US', 'Meeting summary is ready'),
    ('meeting.transcript.ready', 'en-US', 'Meeting transcript is ready')
) AS fixed(topic, locale, label)
WHERE NOT EXISTS (SELECT 1 FROM notify.notification_template t
    WHERE t.template_id = fixed.topic AND t.version = 1 AND t.locale = fixed.locale);
