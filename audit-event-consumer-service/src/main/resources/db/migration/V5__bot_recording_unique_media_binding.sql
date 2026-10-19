-- A call or native media session cannot be admitted under multiple consents,
-- including across companies and after withdrawal. Rejoins require a new call/session.
CREATE UNIQUE INDEX uq_bot_recording_call ON bot_recording_intent
    ((binding_json::jsonb ->> 'callId')) WHERE binding_json IS NOT NULL;
CREATE UNIQUE INDEX uq_bot_recording_media_session ON bot_recording_intent
    ((binding_json::jsonb ->> 'mediaSessionId')) WHERE binding_json IS NOT NULL;
