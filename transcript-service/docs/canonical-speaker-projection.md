# Canonical speaker projection

The previous finalized projection stored only text/start/end, losing anonymous
speaker turns on reopen. The optional `speakerAttribution` field is now captured
with the selected machine text and bound by canonicalProjectionSha256. The
transcript text and transcriptSha256 remain unchanged.

Capture validates source tenant/meeting/session/transport scope, UTF-16 coverage,
surrogate boundaries and window duration. Restore validates the turn contract and
the original projection hash. Editorial text changes and redaction drop stale
attribution. Legacy persisted JSON retains exact property order/null behavior;
legacy reconstruction explicitly excludes attribution because the older source
hash did not bind the full turns.

## Compatible reader rollout

`TRANSCRIPT_FINALIZATION_PERSIST_SPEAKER_ATTRIBUTION=false` is the default. Deploy
all compatible snapshot readers first, then enable capture in TEST. Disabling
capture does not disable reading new-format occurrences. Once new projections
have been written, rollback must keep a compatible reader; do not erase fields
or recalculate historic hashes to make older readers accept them.

GET canonical finalization defaults to the unchanged text/start/end segment DTO
used by the strict meeting-ai consumer. Only `?includeSpeakerAttribution=true`
adds the optional field. Meeting-service opts in on its existing internal call
and returns it through the existing owner-authorized exact analysis-run endpoint.
No permission expansion, capability issuance, erasure or access-audit bypass.

The metadata resides inside the already retained/erasable projection, not in a
new side store. PostgreSQL coverage checks commit/reopen, immutable attribution
after a mutable segment edit, and deletion of both projection and source.

This is a prerequisite for platform-mobile#8's speaker label edit. It does not
identify people or change analysis assignees. Named aliases, their concurrency,
retention and audit semantics, ERP export and device acceptance remain open.
