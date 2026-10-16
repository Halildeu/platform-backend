-- Derived from immutable evidence; existing rows need no mutable-state rewrite.
ALTER TABLE bot_recording_intent ADD COLUMN meeting_id UUID
    GENERATED ALWAYS AS ((grant_json::jsonb ->> 'meetingId')::uuid) STORED NOT NULL;
CREATE INDEX idx_bot_recording_owner_history ON bot_recording_intent
    (owner_issuer, owner_subject, meeting_id, created_at DESC, id DESC);
