# Recording-owned Speechmatics resume

This optional WebSocket protocol retains one live provider bridge across short phone
disconnects. It does not provide recovery after gateway restart or provider failure.
The legacy connection protocol remains available without the query flag.

## Handshake and identity

Initial stream request adds `?resume_protocol=recording-resume-v1`. The authenticated
tenant, owner, recording state and audio format are checked on every attachment.
Speechmatics sessions only are supported. Legacy and resume admission are mutually
exclusive for a recording.

`ready` includes `resume_protocol`, a random `source_epoch`, `replay_after` and
`replay_through`. Final sequence numbers start at zero within this provider bridge.
After accepting a final, the mobile client advances its cursor. Reconnect adds
`source_epoch=<epoch>&after_final=<last accepted sequence>` (initial cursor is -1).
Unknown epochs, future cursors and cursors older than retained history are rejected;
the gateway must never silently replace a missing bridge with a new source.

The reconnect response is ordered: ready, retained finals after the supplied cursor,
`resume_complete` with the same epoch and `replay_through`, then live events. Cached
`eof_ack` precedes a cached terminal event. Snapshot and live subscription switch are
atomic. Mobile validates ready and contiguous replay before flushing queued PCM or
restoring continuity. Physical callbacks are generation-fenced; transcript identity
stays tied to the provider epoch, so a replayed final replaces an interrupted draft.

## Delivery, lifetime and bounds

The provider receiver and transcript persistence continue while no phone is attached.
Mobile `audio_ack` follows the provider's `AudioAdded` receipt, not gateway admission.
At most four provider PCM frames await receipt. A lost-ACK retry repeats the receipt
without resending provider PCM; an in-flight retry is not acknowledged prematurely.
EOF waits for all receipts. Successful completion still requires persisted final
handoff and `drained`; ready, an empty queue or a new bridge cannot prove completion.

The bridge is local to one gateway process. Deployment must keep reconnects on that
process (the current TEST desired overlay has one replica). Gateway restart, routing
to another pod, provider loss or replay expiry fail closed. No durable/multi-pod
continuation is claimed.

- Detach/allocation lease: 60 seconds; provider is cancelled on expiry.
- Provider lifetime: bounded by configured maximum session minutes.
- Final replay: at most 256 events and 1 MiB; old cursors fail closed.
- Physical output: 512 events / 2 MiB; a stalled phone is detached, not the provider.
- Physical input: generation-fenced bounded heap copies, two queue entries, plus
  configured audio/control frame limits and provider backpressure.
- Closed replay cache: absolute 60 seconds from closure, including stalled or repeated
  attachments. Eviction clears replay references and unregisters only its own entry.

Replay contains sensitive transcript text in transient server memory only; no new
raw audio disk persistence or payload logging is introduced. Existing mobile
encryption, buffer TTL and deletion rules are unchanged. Before the initial ready
has supplied an epoch, resuming a lost connection is not supported; capture has not
started at that point.

## Verification scope

Local tests exercise a real TCP provider fixture, real session registry, 30-second
detach, 264 buffered frames, lost receipt retry, exact PCM bytes/hash, detached final
persistence, ordered replay and closure. Other tests cover tenant denial, concurrent
admission, stale socket fencing, overflow, expiry and malformed mobile proof.
These tests do not substitute for deployed Speechmatics and physical-phone acceptance.
Issue #7 stays open until all three offline test phrases and complete closure are
verified against matching deployed gateway and APK revisions.
