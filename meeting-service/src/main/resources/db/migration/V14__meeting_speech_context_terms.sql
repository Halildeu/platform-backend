-- Faz 24 (platform-backend#1024): consent-bound meeting speech-context terms.
-- Opt-in JSON array of normalised STT vocabulary hints; NULL when absent.
-- Lives on the meeting row so it is retained and erased with the meeting.

ALTER TABLE meetings
    ADD COLUMN speech_context_terms JSONB;

ALTER TABLE meetings
    ADD CONSTRAINT meetings_speech_context_terms_array
        CHECK (speech_context_terms IS NULL OR jsonb_typeof(speech_context_terms) = 'array');
