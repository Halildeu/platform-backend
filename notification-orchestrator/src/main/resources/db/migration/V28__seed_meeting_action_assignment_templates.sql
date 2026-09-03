-- Faz 24 Görevler dilim-4b (gitops#3486 / #3537) — fixed-copy, no-content templates
-- for meeting action assignment / hand-over notifications.
--
-- The producer (meeting-service) sends an empty payload. No action text, meeting
-- title, transcript excerpt or assignee identity is rendered: the subscriber opens
-- the Toplantılar workspace ("Görevlerim") to see the task itself.

INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text,
     external_allowed, active, created_by)
SELECT
    'meeting.action.assigned', 1, 'tr-TR',
    'Size yeni bir toplantı görevi atandı',
    '<p>Bir toplantı aksiyonu size görev olarak atandı.</p>'
        || '<p>Ayrıntıları Toplantılar çalışma alanındaki "Görevlerim" bölümünden görüntüleyin.</p>',
    'Bir toplantı aksiyonu size görev olarak atandı.'
        || chr(10)
        || 'Ayrıntıları Toplantılar çalışma alanındaki "Görevlerim" bölümünden görüntüleyin.',
    FALSE, TRUE, 'migration-faz24-gorevler-4b'
WHERE NOT EXISTS (
    SELECT 1 FROM notify.notification_template
    WHERE template_id = 'meeting.action.assigned' AND version = 1 AND locale = 'tr-TR'
);

INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text,
     external_allowed, active, created_by)
SELECT
    'meeting.action.assigned', 1, 'en-US',
    'A meeting task was assigned to you',
    '<p>A meeting action was assigned to you as a task.</p>'
        || '<p>Open "My tasks" in the Meetings workspace to see the details.</p>',
    'A meeting action was assigned to you as a task.'
        || chr(10)
        || 'Open "My tasks" in the Meetings workspace to see the details.',
    FALSE, TRUE, 'migration-faz24-gorevler-4b'
WHERE NOT EXISTS (
    SELECT 1 FROM notify.notification_template
    WHERE template_id = 'meeting.action.assigned' AND version = 1 AND locale = 'en-US'
);

INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text,
     external_allowed, active, created_by)
SELECT
    'meeting.action.reassigned', 1, 'tr-TR',
    'Bir toplantı görevi size devredildi',
    '<p>Bir toplantı görevi size devredildi.</p>'
        || '<p>Ayrıntıları Toplantılar çalışma alanındaki "Görevlerim" bölümünden görüntüleyin.</p>',
    'Bir toplantı görevi size devredildi.'
        || chr(10)
        || 'Ayrıntıları Toplantılar çalışma alanındaki "Görevlerim" bölümünden görüntüleyin.',
    FALSE, TRUE, 'migration-faz24-gorevler-4b'
WHERE NOT EXISTS (
    SELECT 1 FROM notify.notification_template
    WHERE template_id = 'meeting.action.reassigned' AND version = 1 AND locale = 'tr-TR'
);

INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text,
     external_allowed, active, created_by)
SELECT
    'meeting.action.reassigned', 1, 'en-US',
    'A meeting task was handed over to you',
    '<p>A meeting task was handed over to you.</p>'
        || '<p>Open "My tasks" in the Meetings workspace to see the details.</p>',
    'A meeting task was handed over to you.'
        || chr(10)
        || 'Open "My tasks" in the Meetings workspace to see the details.',
    FALSE, TRUE, 'migration-faz24-gorevler-4b'
WHERE NOT EXISTS (
    SELECT 1 FROM notify.notification_template
    WHERE template_id = 'meeting.action.reassigned' AND version = 1 AND locale = 'en-US'
);
