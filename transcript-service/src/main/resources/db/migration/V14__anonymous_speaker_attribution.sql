-- Additive and rollback-compatible: old binaries ignore this nullable column.
-- Attribution follows the transcript row's existing retention/erasure lifecycle.
ALTER TABLE transcript_segments ADD COLUMN speaker_attribution jsonb;
