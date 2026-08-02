-- ES-213 (#3375) — the last two links of the case chain.
--
-- Assignment, assessment and outcome already exist (V8, V9). What a case could not
-- record was what happened to the person found at fault, and whether the person who
-- reported was still all right afterwards. The second omission is the larger one:
-- Directive 2019/1937 exists to protect reporters (art. 19 prohibits retaliation,
-- art. 21 sets out protection), and until now the system closed a case and never
-- asked after them again.

-- ---------------------------------------------------------------------------
-- 1. Sanctions
-- ---------------------------------------------------------------------------
-- The severity scale is a rule, not a spreadsheet. Açık Holding's İHLAL AĞIRLIK
-- CETVELİ scores ten criteria to 1-40 and reads four bands off the total; that
-- mapping is enforced here so two similar violations cannot draw different bands
-- because two people read the table differently.
--
-- The scale also lists violations that are ÇOK AĞIR regardless of score — bribing a
-- public official, sexual harassment, child labour, forced labour, concealing a fatal
-- accident, insider trading, deepfake identity. That escalation is allowed, but never
-- silently: a band above what the score alone supports has to say why.
CREATE TABLE ethics_case_sanctions (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES ethics_cases(id),
    org_id UUID NOT NULL,

    severity_score INTEGER NOT NULL,
    severity_band VARCHAR(16) NOT NULL,
    -- Required only when the band exceeds the score's own band. NULL is the normal case.
    escalation_reason VARCHAR(400),

    sanction_type VARCHAR(40) NOT NULL,

    decided_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- sha256 of the acting subject, matching every other audit surface here: who acted
    -- is answerable, but not readable.
    decided_by_hash VARCHAR(64) NOT NULL,

    -- Applying a sanction and deciding one are different acts by different people on
    -- different days, so they are different columns. A decision with no application is
    -- a real and visible state — it is the backlog this table exists to expose.
    applied_at TIMESTAMP WITH TIME ZONE,
    applied_by_hash VARCHAR(64),
    verification_note VARCHAR(4000),

    appeal_state VARCHAR(16) NOT NULL DEFAULT 'NONE',
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_sanction_score CHECK (severity_score BETWEEN 1 AND 40),
    CONSTRAINT ck_sanction_band CHECK (severity_band IN ('HAFIF','ORTA','AGIR','COK_AGIR')),
    CONSTRAINT ck_sanction_type CHECK (sanction_type IN (
        'NONE','TRAINING','VERBAL_WARNING','WRITTEN_WARNING','SUSPENSION',
        'DEMOTION','TERMINATION','LEGAL_REFERRAL','CONTRACT_TERMINATION')),
    CONSTRAINT ck_sanction_appeal CHECK (appeal_state IN ('NONE','REQUESTED','UPHELD','OVERTURNED')),

    -- The band may not fall below what the score supports. Reading the scale downwards
    -- is how a serious finding quietly becomes a warning.
    CONSTRAINT ck_sanction_band_not_below_score CHECK (
        CASE severity_band
            WHEN 'HAFIF' THEN severity_score <= 10
            WHEN 'ORTA' THEN severity_score <= 20
            WHEN 'AGIR' THEN severity_score <= 30
            ELSE TRUE
        END
    ),
    -- Reading it upwards is legitimate — the automatic-escalation list requires it —
    -- but it has to be justified in writing.
    CONSTRAINT ck_sanction_escalation_justified CHECK (
        escalation_reason IS NOT NULL OR
        CASE severity_band
            WHEN 'HAFIF' THEN severity_score >= 1
            WHEN 'ORTA' THEN severity_score >= 11
            WHEN 'AGIR' THEN severity_score >= 21
            ELSE severity_score >= 31
        END
    ),
    -- Application is all-or-nothing, the same shape as case closure in V9: an applied
    -- sanction carries a date, an actor and a verification note, or it is not applied.
    CONSTRAINT ck_sanction_application CHECK (
        (applied_at IS NULL AND applied_by_hash IS NULL AND verification_note IS NULL)
        OR (applied_at IS NOT NULL AND applied_by_hash IS NOT NULL AND verification_note IS NOT NULL)
    )
);

CREATE INDEX ix_sanctions_case ON ethics_case_sanctions (case_id);
-- The read behind "what has been decided but not yet carried out". The partial form
-- (WHERE applied_at IS NULL) is Postgres-only and lives in db/vendor/postgresql; H2 backs
-- the fast test slices and rejects the syntax outright, so the portable index goes here
-- and the narrower one is added where it is supported.
CREATE INDEX ix_sanctions_pending ON ethics_case_sanctions (org_id, applied_at);

-- ---------------------------------------------------------------------------
-- 2. Retaliation checks
-- ---------------------------------------------------------------------------
-- Three per closed case, at three, six and twelve months — the periods Açık Holding's
-- MDL35 already uses.
--
-- The design point worth stating: this works for an ANONYMOUS reporter. The check is
-- asked through the mailbox they already hold, so protection never requires them to
-- identify themselves. A scheme that could only protect people who gave their name
-- would protect exactly the people who needed it least.
CREATE TABLE ethics_retaliation_checks (
    id UUID PRIMARY KEY,
    case_id UUID NOT NULL REFERENCES ethics_cases(id),
    org_id UUID NOT NULL,

    period_months SMALLINT NOT NULL,
    due_at TIMESTAMP WITH TIME ZONE NOT NULL,

    -- When the question actually went to the reporter, as distinct from when it fell due.
    -- The gap between the two is the only honest measure of whether this is being run.
    asked_at TIMESTAMP WITH TIME ZONE,

    observation VARCHAR(4000),
    risk VARCHAR(16),
    action VARCHAR(4000),
    closed_at TIMESTAMP WITH TIME ZONE,
    closed_by_hash VARCHAR(64),
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT ck_retaliation_period CHECK (period_months IN (3, 6, 12)),
    CONSTRAINT ck_retaliation_risk CHECK (risk IS NULL OR risk IN ('NONE','SUSPECTED','CONFIRMED')),
    -- One check per period per case. Re-running the closure must not quietly create a
    -- second set and make the backlog look worse than it is.
    CONSTRAINT uq_retaliation_case_period UNIQUE (case_id, period_months),
    -- Concluding a check means recording what was seen and how it was judged. A check
    -- closed with no observation is the same empty gesture V9 removed from case closure.
    CONSTRAINT ck_retaliation_closure CHECK (
        (closed_at IS NULL AND closed_by_hash IS NULL AND observation IS NULL AND risk IS NULL)
        OR (closed_at IS NOT NULL AND closed_by_hash IS NOT NULL
            AND observation IS NOT NULL AND risk IS NOT NULL)
    ),
    -- Suspected or confirmed retaliation without a recorded action is a finding nobody
    -- acted on. The directive's protection duty does not end at noticing.
    CONSTRAINT ck_retaliation_action_required CHECK (
        risk IS NULL OR risk = 'NONE' OR action IS NOT NULL
    )
);

CREATE INDEX ix_retaliation_case ON ethics_retaliation_checks (case_id);
-- The sweeper read: what is due and not yet concluded. Partial form in db/vendor/postgresql.
CREATE INDEX ix_retaliation_due ON ethics_retaliation_checks (org_id, due_at);

-- ---------------------------------------------------------------------------
-- 3. What retaliation actually looks like
-- ---------------------------------------------------------------------------
-- Directive 2019/1937 art. 19 does not say "retaliation is prohibited" and stop; it
-- enumerates the forms. That list is the difference between a monitoring programme and
-- a formality. A check that asks "is everything all right?" and takes "yes" for an
-- answer catches nothing — the person being pushed out rarely has the word for it, and
-- the manager doing the pushing never volunteers it. Asking about a named list turns a
-- vague question into a series of answerable ones.
--
-- The vocabulary below is art. 19's own, in the directive's order. It is a closed set on
-- purpose: an org that could add its own categories would drift away from the article
-- the protection is owed under, and a free-text column would make "how often does this
-- happen and where" unanswerable.
CREATE TABLE ethics_retaliation_indicators (
    check_id UUID NOT NULL REFERENCES ethics_retaliation_checks(id),
    indicator VARCHAR(48) NOT NULL,
    PRIMARY KEY (check_id, indicator),
    CONSTRAINT ck_retaliation_indicator CHECK (indicator IN (
        'SUSPENSION',              -- askıya alma, işten çıkarma veya eşdeğeri
        'DEMOTION',                -- rütbe indirimi veya terfi engelleme
        'DUTY_TRANSFER',           -- görev değişikliği, yer değiştirme, ücret indirimi
        'TRAINING_WITHHELD',       -- eğitimden mahrum bırakma
        'NEGATIVE_APPRAISAL',      -- olumsuz performans değerlendirmesi veya referans
        'DISCIPLINARY_MEASURE',    -- disiplin cezası, kınama veya yaptırım
        'COERCION',                -- zorlama, yıldırma, taciz veya dışlama
        'DISCRIMINATION',          -- ayrımcılık, dezavantajlı veya adaletsiz muamele
        'CONTRACT_NOT_CONVERTED',  -- geçici sözleşmenin sürekliye çevrilmemesi
        'CONTRACT_NOT_RENEWED',    -- sözleşmenin yenilenmemesi veya erken feshi
        'REPUTATION_HARM',         -- itibar zedeleme, özellikle sosyal medyada
        'BLACKLISTING',            -- sektörel kara liste veya gayriresmî anlaşma
        'CONTRACT_TERMINATION',    -- mal veya hizmet sözleşmesinin feshi
        'LICENCE_REVOCATION',      -- lisans veya izin iptali
        'PSYCHIATRIC_REFERRAL'     -- psikiyatrik veya tıbbi sevk
    ))
);

CREATE INDEX ix_retaliation_indicator ON ethics_retaliation_indicators (indicator);

COMMENT ON TABLE ethics_retaliation_indicators IS
    'ES-213. Observed forms of retaliation, using Directive 2019/1937 art. 19''s own '
    'enumeration. Closed vocabulary: an org-specific list would drift from the article '
    'the protection is owed under, and free text would make trend analysis impossible.';
