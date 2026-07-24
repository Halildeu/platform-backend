-- Faz 35 ES-208 — fixed-copy, no-PII Etik Speak activity template.
--
-- The producer sends an empty payload and a case-independent intent id. No
-- subject, narrative, category, receipt, reporter identity or case identifier
-- is rendered into email/in-app content.

INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text,
     external_allowed, active, created_by)
SELECT
    'ethics.case.activity', 1, 'tr-TR',
    'Etik bildirim kuyruğunda yeni işlem',
    '<p>Etik bildirim yönetim kuyruğunda yetkili incelemesi gerektiren yeni bir işlem vardır.</p>'
        || '<p>Ayrıntıları yalnızca Etik Speak yönetici ekranından görüntüleyin.</p>',
    'Etik bildirim yönetim kuyruğunda yetkili incelemesi gerektiren yeni bir işlem vardır.'
        || chr(10)
        || 'Ayrıntıları yalnızca Etik Speak yönetici ekranından görüntüleyin.',
    FALSE, TRUE, 'migration-faz35-es208'
WHERE NOT EXISTS (
    SELECT 1 FROM notify.notification_template
    WHERE template_id = 'ethics.case.activity' AND version = 1 AND locale = 'tr-TR'
);

INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text,
     external_allowed, active, created_by)
SELECT
    'ethics.case.activity', 1, 'en-US',
    'New activity in the ethics review queue',
    '<p>New activity requires an authorised review in the ethics management queue.</p>'
        || '<p>Open the Etik Speak manager application to view details.</p>',
    'New activity requires an authorised review in the ethics management queue.'
        || chr(10)
        || 'Open the Etik Speak manager application to view details.',
    FALSE, TRUE, 'migration-faz35-es208'
WHERE NOT EXISTS (
    SELECT 1 FROM notify.notification_template
    WHERE template_id = 'ethics.case.activity' AND version = 1 AND locale = 'en-US'
);
