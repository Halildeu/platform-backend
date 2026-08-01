-- Faz 35 ES-2 (#3271) — acknowledgement templates: tenant-parametric, versioned.
--
-- The art. 9(1)(b) acknowledgement is written by a person, from a draft the system
-- prepares. Templates are data, not code, because the wording is the organisation's
-- voice: a tenant may replace the platform wording (org_id = the tenant) and the
-- platform default (org_id NULL) answers for everyone who has not. Category variants
-- exist because a harassment report needs support-channel wording a fraud report does
-- not; NULL category is the fallback within an org's scope.
--
-- Versioning is append-only: editing a template INSERTs the next version, because the
-- audit ledger records "template X version N was sent" and that reference must still
-- mean the same words years later. The trigger enforcing immutability and the
-- NULL-folding unique scope index are PostgreSQL-only (db/vendor/postgresql/V20);
-- H2 source tests exercise the application contract, the production engine proves
-- the backstop — the same split as every append-only table here.
CREATE TABLE ethics_ack_template (
    id UUID PRIMARY KEY,
    org_id UUID,                     -- NULL = platform default
    category VARCHAR(64),            -- NULL = all categories within this org scope
    version INTEGER NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    CONSTRAINT ck_ethics_ack_template_version CHECK (version >= 1),
    CONSTRAINT ck_ethics_ack_template_body CHECK (length(body) BETWEEN 100 AND 16000)
);

CREATE INDEX ix_ethics_ack_template_scope ON ethics_ack_template (org_id, category, version);

-- Platform default wording, version 1. Fixed UUIDs on purpose: the seed is part of the
-- reviewed schema, and a deterministic identity means the ledger's "template X was sent"
-- resolves identically in every environment. Placeholders are filled at draft time:
-- {{RECEIPT_ID}}, {{FILED_AT}}, {{FEEDBACK_DUE}}. The mandatory sections the acceptance
-- names are all present: process, the three-month feedback promise (art. 9(1)(f)),
-- confidentiality, the retaliation ban, external channels (art. 9(1)(g)) and the way
-- back in. Category variants add to this baseline; they never subtract from it.
INSERT INTO ethics_ack_template (id, org_id, category, version, body, created_at, created_by)
VALUES (
    'f35ac001-0000-4000-8000-000000000001', NULL, NULL, 1,
'Bildiriminiz elimize ulaştı.

{{FILED_AT}} tarihinde ilettiğiniz {{RECEIPT_ID}} numaralı bildirim kayıt altına alındı ve yetkili ekibimiz tarafından incelenmeye başlandı.

Süreç nasıl işleyecek: Bildiriminiz tarafsız olarak değerlendirilecek; gerekli görülürse ek bilgi için bu kanal üzerinden size yazacağız. Değerlendirmenin sonucuna ilişkin geri bildirimi en geç {{FEEDBACK_DUE}} tarihine kadar bu kanaldan alacaksınız.

Gizlilik: Kimliğiniz ve bildiriminizin içeriği, yalnız bu vakayı yürütmekle görevli kişilerin erişebildiği bu kanalda korunur.

Misilleme yasağı: Bildirimde bulunduğunuz için hiçbir yaptırıma, baskıya veya dezavantaja maruz bırakılamazsınız. Böyle bir durumla karşılaşırsanız lütfen bu kanaldan derhal bildirin.

Dış kanallar: Dilerseniz bildiriminizi yetkili resmî makamlara da iletebilirsiniz; bu kanalı kullanmanız o hakkınızı ortadan kaldırmaz.

Bu kanala bildirim numaranız ve erişim sırrınızla her zaman dönebilirsiniz.',
    CURRENT_TIMESTAMP, 'platform-default/v1'
);

INSERT INTO ethics_ack_template (id, org_id, category, version, body, created_at, created_by)
VALUES (
    'f35ac001-0000-4000-8000-000000000002', NULL, 'HARASSMENT_DISCRIMINATION', 1,
'Bildiriminiz elimize ulaştı.

{{FILED_AT}} tarihinde ilettiğiniz {{RECEIPT_ID}} numaralı bildirim kayıt altına alındı ve yetkili ekibimiz tarafından öncelikle incelenmeye başlandı.

Süreç nasıl işleyecek: Bildiriminiz tarafsız olarak değerlendirilecek; gerekli görülürse ek bilgi için bu kanal üzerinden size yazacağız. Değerlendirmenin sonucuna ilişkin geri bildirimi en geç {{FEEDBACK_DUE}} tarihine kadar bu kanaldan alacaksınız.

Destek: Yaşadığınız durumun yıpratıcı olabileceğinin farkındayız. Kurumunuzun sağladığı psikososyal destek imkânlarını kullanmak isterseniz bu kanaldan bilgi talep edebilirsiniz; talebiniz vakayı yürüten ekiple sınırlı kalır.

Gizlilik: Kimliğiniz ve bildiriminizin içeriği, yalnız bu vakayı yürütmekle görevli kişilerin erişebildiği bu kanalda korunur.

Misilleme yasağı: Bildirimde bulunduğunuz için hiçbir yaptırıma, baskıya veya dezavantaja maruz bırakılamazsınız. Böyle bir durumla karşılaşırsanız lütfen bu kanaldan derhal bildirin.

Dış kanallar: Dilerseniz bildiriminizi yetkili resmî makamlara da iletebilirsiniz; bu kanalı kullanmanız o hakkınızı ortadan kaldırmaz.

Bu kanala bildirim numaranız ve erişim sırrınızla her zaman dönebilirsiniz.',
    CURRENT_TIMESTAMP, 'platform-default/v1'
);
