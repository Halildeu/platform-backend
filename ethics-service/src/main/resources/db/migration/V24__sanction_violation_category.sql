-- ES-213 (#3375) follow-up: the automatic-escalation list was declared and never applied.
--
-- CaseSanction.AUTOMATIC_ESCALATIONS named the seven categories the İHLAL AĞIRLIK CETVELİ
-- bands ÇOK AĞIR regardless of score, and nothing read it: the record API carried no
-- violation category, so the rule had nothing to fire on. A live probe recorded a
-- termination for a category on that list at any band the caller liked.
--
-- The category becomes part of the record, and the floor is enforced here as well as in
-- the entity, because a sanctions register that can be written to by a migration, a
-- backfill, or a future second code path needs the invariant to live where the rows do.

ALTER TABLE ethics_case_sanctions
    ADD COLUMN violation_category VARCHAR(40);

-- Existing rows predate the column. They are backfilled to UNSPECIFIED rather than to a
-- guessed category: inventing a category for a decision somebody already took would put
-- words in an auditor's mouth. UNSPECIFIED is not on the escalation list, so the new
-- constraint accepts these rows unchanged and they stay visibly distinguishable from
-- anything recorded after this migration.
UPDATE ethics_case_sanctions SET violation_category = 'UNSPECIFIED' WHERE violation_category IS NULL;

ALTER TABLE ethics_case_sanctions
    ALTER COLUMN violation_category SET NOT NULL;

ALTER TABLE ethics_case_sanctions
    ADD CONSTRAINT ck_sanction_automatic_escalation_floor CHECK (
        violation_category NOT IN (
            'PUBLIC_OFFICIAL_BRIBERY',
            'SEXUAL_HARASSMENT',
            'CHILD_LABOUR',
            'FORCED_LABOUR',
            'CONCEALED_FATAL_ACCIDENT',
            'INSIDER_TRADING',
            'FORGED_IDENTITY'
        )
        OR severity_band = 'COK_AGIR'
    );
