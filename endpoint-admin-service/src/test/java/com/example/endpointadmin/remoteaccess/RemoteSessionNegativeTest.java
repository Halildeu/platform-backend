package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 broker negative-test plan (ADR-0033 §9 → test plan; plan §9.1). The four mandated
 * fail-closed denials that gate the first live session (ADR-0034 §11/D10):
 * <ol>
 *   <li>self-approval deny</li>
 *   <li>expired / replayed token deny</li>
 *   <li>capability-mismatch deny</li>
 *   <li>recorder-unavailable deny</li>
 * </ol>
 */
class RemoteSessionNegativeTest {

    /** Simple in-memory single-use jti cache for tests. */
    private static RemoteSessionTokenValidator.JtiReplayCache freshCache() {
        Set<String> seen = new HashSet<>();
        return seen::add; // add() returns true iff newly inserted == recordIfAbsent semantics
    }

    private static RemoteSessionToken tokenBound(String jti, Instant issued, Duration ttl,
                                                 Set<RemoteSessionCapability> caps) {
        return new RemoteSessionToken(jti, "kid-1", "sess-1", "dev-1", "op-1", "broker",
                caps, issued, issued.plus(ttl));
    }

    // ---- 1. self-approval deny ----
    @Test
    void selfApprovalIsDenied() {
        assertFalse(RemoteSessionAuthz.approverDistinctFromRequester("user-7", "user-7"),
                "approver == requester must be denied (no self-approval)");
        assertTrue(RemoteSessionAuthz.approverDistinctFromRequester("user-7", "user-9"));
        // fail-closed on blank/null principals
        assertFalse(RemoteSessionAuthz.approverDistinctFromRequester("user-7", " "));
        assertFalse(RemoteSessionAuthz.approverDistinctFromRequester(null, "user-9"));
        // whitespace-normalized: " user-7 " is NOT distinct from "user-7" (Codex absorb — trim)
        assertFalse(RemoteSessionAuthz.approverDistinctFromRequester("user-7", " user-7 "),
                "whitespace-padded same id must still be denied");
    }

    // ---- 2a. expired token deny ----
    @Test
    void expiredTokenIsDenied() {
        Instant issued = Instant.parse("2026-06-11T10:00:00Z");
        RemoteSessionToken token = tokenBound("jti-exp", issued, Duration.ofMinutes(30),
                RemoteSessionCapability.PILOT_ALLOWED);
        var validator = new RemoteSessionTokenValidator(freshCache());
        Instant afterExpiry = issued.plus(Duration.ofMinutes(31));
        assertEquals(RemoteSessionTokenValidator.Decision.DENY_EXPIRED,
                validator.validate(token, afterExpiry, "sess-1", "dev-1", "op-1",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)));
    }

    // ---- 2b. replayed token deny ----
    @Test
    void replayedTokenIsDenied() {
        Instant issued = Instant.parse("2026-06-11T10:00:00Z");
        RemoteSessionToken token = tokenBound("jti-replay", issued, Duration.ofMinutes(30),
                RemoteSessionCapability.PILOT_ALLOWED);
        var validator = new RemoteSessionTokenValidator(freshCache());
        Instant now = issued.plus(Duration.ofMinutes(1));
        Set<RemoteSessionCapability> req = Set.of(RemoteSessionCapability.VIEW_ONLY);
        // first use accepted
        assertEquals(RemoteSessionTokenValidator.Decision.ACCEPT,
                validator.validate(token, now, "sess-1", "dev-1", "op-1", req));
        // second use of the same jti denied (single-use)
        assertEquals(RemoteSessionTokenValidator.Decision.DENY_REPLAYED,
                validator.validate(token, now, "sess-1", "dev-1", "op-1", req));
    }

    // ---- 2c. token-over-4h hard cap deny (malformed) ----
    @Test
    void tokenExceedingFourHourCapIsDenied() {
        Instant issued = Instant.parse("2026-06-11T10:00:00Z");
        RemoteSessionToken token = tokenBound("jti-long", issued, Duration.ofHours(5),
                RemoteSessionCapability.PILOT_ALLOWED);
        var validator = new RemoteSessionTokenValidator(freshCache());
        assertEquals(RemoteSessionTokenValidator.Decision.DENY_MALFORMED,
                validator.validate(token, issued.plusSeconds(60), "sess-1", "dev-1", "op-1",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)));
    }

    // ---- 2c-bis. blank jti deny (replay-key integrity, Codex absorb) ----
    @Test
    void blankJtiIsDeniedMalformed() {
        Instant issued = Instant.parse("2026-06-11T10:00:00Z");
        RemoteSessionToken token = tokenBound("   ", issued, Duration.ofMinutes(30),
                RemoteSessionCapability.PILOT_ALLOWED);
        var validator = new RemoteSessionTokenValidator(freshCache());
        assertEquals(RemoteSessionTokenValidator.Decision.DENY_MALFORMED,
                validator.validate(token, issued.plusSeconds(60), "sess-1", "dev-1", "op-1",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)));
    }

    // ---- 2d. binding mismatch deny (pass-the-hash guard) ----
    @Test
    void tokenBoundToAnotherDeviceIsDenied() {
        Instant issued = Instant.parse("2026-06-11T10:00:00Z");
        RemoteSessionToken token = tokenBound("jti-bind", issued, Duration.ofMinutes(30),
                RemoteSessionCapability.PILOT_ALLOWED);
        var validator = new RemoteSessionTokenValidator(freshCache());
        assertEquals(RemoteSessionTokenValidator.Decision.DENY_BINDING_MISMATCH,
                validator.validate(token, issued.plusSeconds(60), "sess-1", "OTHER-DEVICE", "op-1",
                        Set.of(RemoteSessionCapability.VIEW_ONLY)));
    }

    // ---- 3. capability-mismatch deny (agent may only downscope, never widen) ----
    @Test
    void capabilityNotInApprovedAllowlistIsDenied() {
        Instant issued = Instant.parse("2026-06-11T10:00:00Z");
        // token approved for VIEW_ONLY only
        RemoteSessionToken token = tokenBound("jti-cap", issued, Duration.ofMinutes(30),
                Set.of(RemoteSessionCapability.VIEW_ONLY));
        var validator = new RemoteSessionTokenValidator(freshCache());
        // operator requests FILE_TRANSFER (not approved, and not even pilot-allowed)
        assertEquals(RemoteSessionTokenValidator.Decision.DENY_CAPABILITY_MISMATCH,
                validator.validate(token, issued.plusSeconds(60), "sess-1", "dev-1", "op-1",
                        Set.of(RemoteSessionCapability.VIEW_ONLY, RemoteSessionCapability.FILE_TRANSFER)));
        // file-transfer / clipboard are not pilot-allowed regardless
        assertFalse(RemoteSessionCapability.FILE_TRANSFER.isPilotAllowed());
        assertFalse(RemoteSessionCapability.CLIPBOARD_SYNC.isPilotAllowed());
        assertEquals(Set.of(RemoteSessionCapability.VIEW_ONLY, RemoteSessionCapability.CONSTRAINED_PTY),
                RemoteSessionCapability.PILOT_ALLOWED);
    }

    // ---- 4. recorder-unavailable deny (recording atomic with session; fail-closed) ----
    @Test
    void recorderUnavailableBlocksActive() {
        RemoteSessionStateMachine sm = new RemoteSessionStateMachine();
        RemoteSessionPreconditions noRecorder =
                new RemoteSessionPreconditions(true, true, true, true, true, false);
        assertFalse(sm.canActivate(RemoteSessionState.RECORDING_READY, noRecorder));
        assertEquals(RemoteSessionState.FAILED_RECORDING,
                sm.transition(RemoteSessionState.RECORDING_READY, RemoteSessionState.ACTIVE, noRecorder));
    }
}
