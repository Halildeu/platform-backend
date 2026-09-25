# Teams SDK audio receiver: source readiness and integration boundary

This is an isolated library for an eventual Windows media host. It references the
actual `Microsoft.Skype.Bots.Media` SDK, copies and owns incoming unmixed PCM, and
maps source IDs against a bounded history of participant observations. The current
`TeamsCapture.Worker` does **not** reference it. No live-audio readiness flag,
service-hosted call behavior, mobile/Electron behavior, or deployment is changed.

## What is implemented

- Receive-only PCM16, 16 kHz, mono, with `ReceiveUnmixedMeetingAudio = true`.
- An `IAudioSocket.AudioMediaReceived` subscription; native buffers are disposed on
  every callback path. Unmixed slices are copied before their owner is disposed.
- A bounded, nonblocking callback queue. Invalid format, clock discontinuity,
  missing unmixed data and overflow terminate processing and clear pending audio.
  A host must observe `Failure`; queue completion is not proof of successful capture.
- Processing starts closed. Terminal revocation detaches the event, zeroes queued
  PCM and clears identity history. Consumers own and must dispose delivered frames.
- Exact tenant/meeting/call/media-session scope. Names alone never identify a
  participant. Duplicate sources, lobby participants, stale or unavailable rosters,
  and transitions remain unattributed. Once a source ID is reused by another
  participant/user, it stays quarantined for that media session.
- Attribution is resolved immediately before dequeue ownership transfer, against
  the frame's original receiving timestamp and duration. A roster invalidation or
  reuse discovered while queued cannot preserve an earlier matched name. Subsequent
  observations cannot revise already delivered frames; downstream evidence must
  retain scope, source ID and timestamps and support later attribution correction.

## Required host integration (not implemented by this library)

1. Start and configure a supported native Windows media runtime using the approved
   host, certificate and networking. Validate Azure Bot/Entra calling configuration.
2. Join with **application-hosted media from call creation**, using the local media
   session's configuration. The existing service-hosted presence call is not an
   audio source and must not be presented as a completed media implementation.
3. Subscribe to authoritative SDK participant changes for that exact call/session.
   Feed full snapshots into `ApplySnapshot`, with strictly increasing revision and
   receiving-media-clock observations in 100 ns ticks. Refresh authoritative state
   within the configured roster TTL. Do not relabel a cached REST roster with a new
   timestamp or use UTC, `OriginalSenderTimestamp`, a UI highlight, or display name
   as this clock/identity proof. Invalid or out-of-order snapshots clear the map.
4. Confirm current tenant/meeting authorization, recording consent and Microsoft's
   required recording-status acknowledgment before `AllowProcessing`. There is no
   public HTTP toggle. Revoke on any permission/consent loss or call termination.
   A caller-provided key is a scope check, **not authentication**.
5. Deliver each owned frame only over an authenticated, meeting-scoped STT ingest
   path, with a fresh permission check before downstream I/O. Dispose it even on
   cancellation/failure. Frames already transferred are the consumer's responsibility.
   Existing user-JWT audio endpoints must not be called with an unrelated worker
   token. This transport and downstream authorization are still to be implemented.
6. Reconcile terminal failures and roster/clock discontinuities through a new
   receiver/session; do not silently resume an old queue or infer an identity.

Receiving time is not source capture time. Roster/audio network reordering remains
a real integration limitation, and the host must report uncertainty rather than
claiming every frame is identity-verified. The SDK provides up to four dominant
unmixed speakers, not an unlimited recording track for every attendee. A shared
room microphone identifies its endpoint, not the individual people in that room.

## Verification without tenant credentials

Run on Windows x64 with .NET 8:

```powershell
dotnet restore teams-capture-worker/tests/TeamsCapture.Media.Tests/TeamsCapture.Media.Tests.csproj --locked-mode --configfile teams-capture-worker/NuGet.Config -p:NuGetAuditMode=all
dotnet test teams-capture-worker/tests/TeamsCapture.Media.Tests/TeamsCapture.Media.Tests.csproj --no-restore --configuration Release
dotnet list teams-capture-worker/tests/TeamsCapture.Media.Tests/TeamsCapture.Media.Tests.csproj package --vulnerable --include-transitive
```

The suite exercises actual SDK types/event signatures with a simulated socket and
owned unmanaged test buffers. It does not load a live media platform or prove Teams
audio delivery. Tests cover two sources, no future-identity borrowing, source reuse,
queued attribution invalidation, permission revocation, late callbacks, scope
isolation, stale/out-of-order rosters, disposal and overload. Windows CI runs the
same suite, with dependency locks and all-transitive vulnerability auditing.

SDK transitive defaults include obsolete native SQLite, regex and text-encoding
packages. Explicit pins in this new library replace them; an in-memory SQLite
query verifies that the effective native library is at least 3.50.2 and compatible
with the SDK's managed dependency. These overrides do not modify the current worker.

Still required for acceptance: the host and transport integration above, approved
infrastructure/permissions, and a two-person real Teams test with joining/leaving,
overlapping speech, revoked consent, name/source mapping, live actions/decisions,
panel delivery and shutdown. CI passing cannot close that acceptance.

## SDK contracts

- [Application-hosted media requirements](https://learn.microsoft.com/en-us/microsoftteams/platform/bots/calls-and-meetings/requirements-considerations-application-hosted-media-bots)
- [Audio socket settings](https://microsoftgraph.github.io/microsoft-graph-comms-samples/docs/bot_media/Microsoft.Skype.Bots.Media.AudioSocketSettings.html)
- [Native receive buffer and receiving clock](https://microsoftgraph.github.io/microsoft-graph-comms-samples/docs/bot_media/Microsoft.Skype.Bots.Media.AudioMediaBuffer.html)
- [Unmixed source ID and sender timestamp](https://microsoftgraph.github.io/microsoft-graph-comms-samples/docs/bot_media/Microsoft.Skype.Bots.Media.UnmixedAudioBuffer.html)
