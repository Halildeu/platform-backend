-- #869 privacy lifecycle guard for immutable transcript finalizations.
-- Existing rows remain readable and default to no legal hold.
ALTER TABLE transcript_finalizations
    ADD COLUMN legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN canonical_transcript TEXT,
    ADD COLUMN canonical_transcript_sha256 VARCHAR(64),
    ADD COLUMN canonical_segments JSONB,
    ADD COLUMN canonical_projection_sha256 VARCHAR(64),
    ADD CONSTRAINT ck_transcript_finalization_canonical_projection_complete
        CHECK (
            (canonical_transcript IS NULL
             AND canonical_transcript_sha256 IS NULL
             AND canonical_segments IS NULL
             AND canonical_projection_sha256 IS NULL)
            OR
            (canonical_transcript IS NOT NULL
             AND canonical_transcript_sha256 IS NOT NULL
             AND canonical_transcript_sha256 ~ '^[0-9a-f]{64}$'
             AND canonical_segments IS NOT NULL
             AND jsonb_typeof(canonical_segments) = 'array'
             AND canonical_projection_sha256 IS NOT NULL
             AND canonical_projection_sha256 ~ '^[0-9a-f]{64}$')
        );

CREATE INDEX idx_transcript_finalizations_retention
    ON transcript_finalizations (created_at, id)
    WHERE legal_hold = FALSE;

COMMENT ON COLUMN transcript_finalizations.legal_hold IS
    'When true, automated transcript/finalization destruction is blocked; authorized reads remain auditable.';

COMMENT ON COLUMN transcript_finalizations.canonical_transcript IS
    'Immutable selected transcript text for this exact finalization occurrence; erased with its privacy lifecycle.';

COMMENT ON COLUMN transcript_finalizations.canonical_segments IS
    'Immutable ordered text/timing projection for this exact finalization occurrence; contains personal data.';
