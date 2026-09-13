# Anonymous Speaker Attribution

Tracked by Halildeu/platform-k8s-gitops#3740; customer slice #3399.

The realtime Speechmatics adapter requests `diarization: speaker`. It never
requests speaker identifiers, enrolment, voiceprints or named speaker matching.
Reference: https://docs.speechmatics.com/api-ref/realtime-transcription-websocket

## Wire Contract

The optional `speakerAttribution` object contains `scope` (UUID) and `turns`.
Each turn contains only `speaker`, `textStart`, `textEnd`, `startMs`, `endMs`.
Text positions are UTF-16 offsets in the exact, unchanged parent transcript.
Times are milliseconds relative to that parent source audio window, not receipt
time or inference latency. Text spans are ordered and non-overlapping; acoustic
spans may overlap. Whitespace between spans is preserved. Labels are anonymous
`S1` through `S999`, `SPEAKER_00` style labels, or `UU` for unknown.

`scope` binds tenant, meeting, source session and gateway transport epoch. A new
connection starts a new speaker namespace; matching `S1` labels across namespaces
is NOT evidence of the same person. The client numbers observed namespace/label
pairs for display. Unknown labels never acquire an inferred identity.

Only events containing attribution use `audioGateway.directSttTranscriptResult.v2`.
The existing v1 envelope remains valid and does not imply attribution. Consumers
reject a v2 scope mismatch, malformed/range-invalid metadata, or attribution in a
v1 envelope. Provider word/text mismatch preserves the complete transcript without
attribution, rather than replacing words or fabricating an alignment.

## Persistence and Delivery

The existing source-window idempotency key is unchanged. The nullable JSONB column
`speaker_attribution` follows the transcript row's erasure and retention lifecycle.
Replay equality includes attribution. The existing scalar `speaker_id` is populated
only if every turn in the window belongs to the same known speaker. Mixed windows
keep the scalar null and expose their complete attribution through the read DTO.
If editorial text differs, old offsets are not presented as valid attribution.

Gateway WebSocket, SSE and owner-scoped polling expose the same attribution.
Attributed windows are standalone assembly projections, so sentence folding cannot
erase speaker changes. Projection provenance uses the actual durable source event
id when available, permitting clients to remove folded draft duplicates.

## Deployment and Evidence

Deploy the v1/v2 transcript consumer before enabling the new producer. The additive
column is rollback-compatible; roll back the producer first, then the consumer.
Do not deploy both in an unordered rollout that lets old consumers dead-letter v2.
Internal STT remains the default; this change does not add a diarization engine to
the internal live-STT runtime or the independent REST-window Speechmatics lane.

Tests distinguish contract/synthetic evidence from measured acoustic WER/DER and
real customer acceptance. No model-quality improvement is claimed by persistence,
CI, or this schema alone. External provider acceptance uses synthetic audio only;
the existing real-audio/production/privacy gates remain unchanged.
