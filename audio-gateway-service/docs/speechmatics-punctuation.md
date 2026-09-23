# Speechmatics live punctuation

Provider word-final events are transport fragments, not UI paragraphs. The adapter
keeps the provider transcript authoritative, including punctuation, source timing
and speaker spans; it never guesses which full stops after names to delete.

`AUDIO_GATEWAY_DIRECT_STT_SPEECHMATICS_PUNCTUATION_SENSITIVITY` optionally sets
`transcription_config.punctuation_overrides.sensitivity` in StartRecognition.
It must be finite and between 0 and 1. Unset preserves the provider default (0.5).
Lower values request fewer marks, but do not guarantee correct grammar or owners.
All punctuation types remain enabled. This applies only to newly opened sessions.

Reference: https://docs.speechmatics.com/api-ref/realtime-transcription-websocket

Choose a TEST value from the pinned synthetic name/pauses comparison in GitOps
`scripts/faz24/probe_name_punctuation.py`, then verify real speech and downstream
decisions/owners. Do not infer success from an accepted setting alone. Shared TEST
configuration remains GitOps controlled; rollback removes that overlay setting.
No client-specific setting or Electron change is introduced by this source change.
