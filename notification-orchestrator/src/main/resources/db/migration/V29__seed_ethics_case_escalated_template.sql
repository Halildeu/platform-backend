-- Faz 35 ES-301b (#1153) — fixed-copy, no-PII Etik Speak escalation template.
--
-- The producer (ethics-service) sends exactly one payload variable, the integer
-- escalation level, and a case-independent intent id. Level 1 is the first tier's
-- signal; levels 2+ are addressed to the second tier (compliance / board). No
-- subject, narrative, category, receipt, reporter identity or case identifier is
-- rendered. The level is Thymeleaf's escaped inline expression `[[${vars.level}]]`, valid in
-- both the TEXT parts (subject, body_text) and the HTML body; it is spelled below as
-- '[[' || '$' || '{vars.level}]]' because Flyway would otherwise read `${vars.level}` as one
-- of its own placeholders and refuse the migration.

INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text,
     external_allowed, active, created_by)
SELECT
    'ethics.case.escalated', 1, 'tr-TR',
    'Etik vakası eskalasyonu — seviye [[' || '$' || '{vars.level}]]',
    '<p>Bir etik vakası yasal süresini aştı ve [[' || '$' || '{vars.level}]]. eskalasyon seviyesine ulaştı.</p>'
        || '<p>Ayrıntıları yalnızca Etik Speak yönetici ekranından görüntüleyin.</p>',
    'Bir etik vakası yasal süresini aştı ve [[' || '$' || '{vars.level}]]. eskalasyon seviyesine ulaştı.'
        || chr(10)
        || 'Ayrıntıları yalnızca Etik Speak yönetici ekranından görüntüleyin.',
    FALSE, TRUE, 'migration-faz35-es301b'
WHERE NOT EXISTS (
    SELECT 1 FROM notify.notification_template
    WHERE template_id = 'ethics.case.escalated' AND version = 1 AND locale = 'tr-TR'
);

INSERT INTO notify.notification_template
    (template_id, version, locale, subject, body_html, body_text,
     external_allowed, active, created_by)
SELECT
    'ethics.case.escalated', 1, 'en-US',
    'Ethics case escalation — level [[' || '$' || '{vars.level}]]',
    '<p>An ethics case has passed its legal deadline and reached escalation level [[' || '$' || '{vars.level}]].</p>'
        || '<p>Open the Etik Speak manager application to view details.</p>',
    'An ethics case has passed its legal deadline and reached escalation level [[' || '$' || '{vars.level}]].'
        || chr(10)
        || 'Open the Etik Speak manager application to view details.',
    FALSE, TRUE, 'migration-faz35-es301b'
WHERE NOT EXISTS (
    SELECT 1 FROM notify.notification_template
    WHERE template_id = 'ethics.case.escalated' AND version = 1 AND locale = 'en-US'
);
