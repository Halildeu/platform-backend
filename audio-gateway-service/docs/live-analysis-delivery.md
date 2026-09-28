# Live analysis delivery while recording

Decisions and actions must arrive through the authenticated meeting SSE stream
while capture continues. A final persisted result after stopping is a separate
acceptance condition, not proof of live delivery.

## Accumulation and delivery

- `segment-window` (default 5) may flush early when enough final fragments arrive.
- `max-wait-ms` (default 15000; range 100..300000) flushes dirty short windows
  from the first unflushed fragment, even if the speaker pauses.
- `min-interval-ms` still bounds request starts. There is one in-flight request
  per meeting; newer cumulative snapshots coalesce. No unchanged/empty timer
  requests are sent. Closing the trigger cancels its timers and requests.
- The maximum accumulation wait is **not** an end-to-end latency guarantee:
  existing cadence and upstream analysis time are additional.
- SSE subscription registration and last-viewer removal are atomic per meeting.
  One viewer leaving must not detach another viewer's stream.

## Diagnostics and deployment acceptance

Existing attempt/success/error counters are supplemented by
`audio_gw_live_analyze_request_seconds` duration metrics. Failure logs carry only
meeting id, sequence, exception class, HTTP status (0 for non-HTTP errors), and
elapsed milliseconds; never body text, headers, credentials or error messages.
Compare these with mobile SSE byte/heartbeat/accepted/rejected-frame counts.

Before acceptance, record the running gateway image digest and effective live
configuration, then trace a permitted synthetic meeting's final transcript to
the HTTP request, verified response and mobile decision/action receipt **before
stop**. Include a short utterance followed by silence and two viewers with one
leaving. Confirm the final durable result independently.

The hub remains process-local and has no replay. Multiple gateway replicas
require a shared live fan-out design or proven affinity for both capture and
SSE. A desired replica count of one does not prove the current process topology.

The Android report from 2026-09-21 (50 final text events, zero analysis snapshots)
does not contain upstream request timing/status or rejected SSE-frame counts.
These source fixes therefore do not establish that historical incident's root
cause or replace exact-runtime/phone acceptance.
