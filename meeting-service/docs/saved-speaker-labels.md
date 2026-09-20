# Saved speaker display labels

Source proposal for platform-mobile #8; builds on the immutable anonymous speaker
projection. This document does not record a rollout or physical-device acceptance.

## Contract and authority

Public GET/PUT:
`/api/v1/admin/meetings/{meetingId}/intelligence/results/{analysisRunId}/transcript/speaker-labels`.
GET requires meeting scope, module can_view and meeting ownership. PUT additionally
requires module can_manage. The public `editable` value includes that permission,
legal-hold status and the existence of known, frozen speaker attribution. Permission
failure must never hide the original full transcript.

PUT changes one key: `{scope, speaker, name, expectedRevision}`. `name:null` removes
that key; all other names remain. Names are not person identification or action
assignee inference. Same display names on separate speaker keys do not merge them.
The key is the immutable projection scope UUID plus anonymous speaker ID, never
the locally assigned display number. Unknown UU and legacy/unattributed text have
no editable speaker identity. A new analysis occurrence has its own label list.

Response includes tenant/meeting/session/version/run/transcript hash, revision,
editable, labels. Meeting-service translates its public analysis-run identity to
the producer occurrence identity for the internal call and validates the entire
response tuple. Both clients validate the exact acknowledgement. The actor comes
from authenticated identity, never a mobile body or caller-supplied actor header.

Names contain 1–80 Unicode code points, at most 160 UTF-16 code units, with leading
and trailing Unicode Zs spaces stripped. Raw control, format/bidi, unpaired surrogate
and paragraph separators are rejected. There are at most 256 names per occurrence;
the write body is capped at 4096 bytes including chunked bodies, stored JSON at
131072 UTF-8 bytes. No-store applies to label responses.

## Persistence and races

Transcript migration V15 adds two companion columns to transcript_finalizations.
Canonical text, segment projection and their hashes remain immutable. Label writes
take the session erasure fence, lock the exact occurrence row, recheck erasure and
legal hold, then perform a narrow compare-and-swap update and metadata-only audit
in one transaction. An audit failure rolls back the change. General JPA saves have
these columns updatable=false, preventing stale legal-hold updates from resetting
newer labels. Native SQL uses the Hibernate schema placeholder, not search_path.

Erasure and retention delete the same finalization row, including names. A pending
erasure blocks writes; completed erasure returns gone. A legally held row is read
only, including when pending erasure retained it. A conflicting revision returns
409. On uncertain/lost reply, the mobile editor reads back once and asks the user
to inspect current names; it never silently retries the old edit with a newer
revision. Switching account/meeting/occurrence invalidates pending UI responses.

No name is added to Kafka analysis events, logs, access audit payloads, immutable
transcript text, raw-text copy or PDF. The speaker display is a separate user layer.

## Explicit TEST activation, after source review

1. Apply the additive transcript migration and compatible transcript/meeting/auth
   sources. Keep MEETING_SPEAKER_LABELS_ENABLED=false during preparation.
2. Preserve the existing auth grant lists and explicitly append ONLY
   `transcript:speaker-label:read` and `transcript:speaker-label:write` to all three:
   - SERVICE_CLIENT_MEETING_SERVICE_ALLOWED_PERMISSIONS;
   - SERVICE_CLIENT_MEETING_SERVICE_TRANSCRIPT_ALLOWED_PERMISSIONS;
   - SECURITY_SERVICE_MINT_ALLOWED_PERMISSIONS.
   These lists are replacement values, so copy existing effective values first.
   Shipped defaults grant neither new permission. No permission is added to
   meeting-ai or to a non-transcript audience. Existing secret management is used;
   no secret is committed or provisioned by this source change.
3. Preserve ADR0020's reader-before-writer rollout for frozen speaker attribution.
   Only after compatible readers exist may
   TRANSCRIPT_FINALIZATION_PERSIST_SPEAKER_ATTRIBUTION be enabled. Old recordings
   without attribution cannot safely gain invented editable speaker keys.
4. Enable MEETING_SPEAKER_LABELS_ENABLED in the reviewed TEST meeting-service.
5. On a fresh authorized recording, verify read/edit/reopen, two-device revision
   conflict, viewer-owner read-only UI, legal hold, deletion, logout/account switch,
   unchanged raw copy/PDF, and no impact on live analysis or persisted result.

To withdraw the feature, disable the meeting flag and remove the new auth grants
while keeping pre-existing grants. Do not roll back the speaker-attribution reader
to an incompatible version, delete stored labels, or downgrade migrations as part
of this switch. Existing names follow their occurrence retention/erasure policy.
