-- V15 is reserved by PR1176 for independent notification delivery.
-- Editable companion metadata; canonical text and projection hashes stay immutable.
ALTER TABLE transcript_finalizations
    ADD COLUMN speaker_labels JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN speaker_labels_revision BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT transcript_speaker_labels_array CHECK (
        jsonb_typeof(speaker_labels) = 'array' AND jsonb_array_length(speaker_labels) <= 256),
    ADD CONSTRAINT transcript_speaker_labels_revision_range CHECK (
        speaker_labels_revision >= 0 AND speaker_labels_revision <= 9007199254740991);
COMMENT ON COLUMN transcript_finalizations.speaker_labels IS
    'User display labels scoped to this exact occurrence and anonymous speaker scope/id. Personal data: erased and retained with the containing row; never copied to events or audit.';
