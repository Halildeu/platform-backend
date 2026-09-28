import com.example.common.meeting.events.RecordingOutcome;
import com.example.meeting.security.AnalysisJobCapabilityVerifier;
import com.example.transcript.security.AnalysisJobCapabilityIssuer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Executes the real issuer and verifier together, without a service-to-service build dependency. */
class RecordingCapabilityRoundTrip {
    public static void main(String[] args) {
        String secret = Base64.getEncoder().encodeToString(
                (UUID.randomUUID().toString() + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        var issuer = new AnalysisJobCapabilityIssuer(secret, Duration.ofMinutes(5),
                "transcript-service", "meeting-service", "meeting-ai");
        var verifier = new AnalysisJobCapabilityVerifier(secret, "transcript-service",
                "meeting-service", "meeting-ai", Duration.ofMinutes(5));
        UUID tenant = UUID.randomUUID(), meeting = UUID.randomUUID(), session = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        Instant finalized = Instant.parse("2026-09-26T18:53:00.123456Z");
        for (RecordingOutcome outcome : RecordingOutcome.values()) {
            String reason = outcome == RecordingOutcome.INCOMPLETE ? "CLOSURE_UNCONFIRMED" : null;
            var signed = issuer.issue(new AnalysisJobCapabilityIssuer.JobBinding(tenant, meeting, session,
                    7L, finalized, "a".repeat(64), run, "meeting-intelligence-v1", outcome, reason));
            var verified = verifier.verify(signed.token());
            check(verified.capabilityId().equals(signed.capabilityId()), "capability identity");
            check(verified.tenantId().equals(tenant) && verified.meetingId().equals(meeting)
                    && verified.sessionId().equals(session), "canonical scope");
            check(verified.analysisRunId().equals(run) && verified.finalizationVersion() == 7L
                    && verified.finalizedAt().equals(finalized), "immutable occurrence");
            check(verified.transcriptSha256().equals("a".repeat(64))
                    && verified.analysisSpecVersion().equals("meeting-intelligence-v1"), "analysis binding");
            check(verified.recordingOutcome() == outcome
                    && Objects.equals(verified.recordingIncompleteReason(), reason), "recording closure");
            System.out.println("PASS real issuer -> verifier: " + outcome);
        }
    }

    private static void check(boolean condition, String boundary) {
        if (!condition) throw new AssertionError("Capability round trip failed: " + boundary);
    }
}
