package com.example.transcript.directstt;

import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.model.TranscriptSessionAssociationStatus;
import com.example.transcript.directstt.TranscriptSessionAssociationStore.SessionAssociationConflictException;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Resolves and pins external recorder ids with bounded, lease-fenced retries.
 *
 * <p>Only metadata crosses this boundary. Missing mappings remain PENDING and
 * are never reported as a successfully accepted transcript segment.
 */
@Service
public class TranscriptSessionAssociationService {

    private final TranscriptSessionAssociationStore store;
    private final MeetingSessionResolver resolver;
    private final DirectSttTranscriptResultConsumerProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    @Autowired
    public TranscriptSessionAssociationService(
            TranscriptSessionAssociationStore store,
            MeetingSessionResolver resolver,
            DirectSttTranscriptResultConsumerProperties properties,
            MeterRegistry meters) {
        this(store, resolver, properties, meters, Clock.systemUTC());
    }

    TranscriptSessionAssociationService(
            TranscriptSessionAssociationStore store,
            MeetingSessionResolver resolver,
            DirectSttTranscriptResultConsumerProperties properties,
            MeterRegistry meters,
            Clock clock) {
        this.store = store;
        this.resolver = resolver;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    public Outcome resolve(DirectSttTranscriptResultEvent event) {
        return resolve(event.tenantId(), event.meetingId(), event.sourceSessionId());
    }

    public Outcome resolve(UUID tenantId, UUID meetingId, String sourceSessionId) {
        Instant now = clock.instant();
        TranscriptSessionAssociation association = store.ensurePending(
                deterministicId(tenantId, meetingId, sourceSessionId),
                tenantId, meetingId, sourceSessionId, now);
        int recoveredAttempt = association.getResolutionAttempts() + 1;
        if (store.recoverStale(
                tenantId, meetingId, sourceSessionId,
                now.plusMillis(backoffMillis(recoveredAttempt)), now)) {
            association = store.require(tenantId, meetingId, sourceSessionId);
            meters.counter("transcript_session_mapping_lease_expired_total", "outcome", "retry")
                    .increment();
            return terminalOrPending(association);
        }
        Outcome terminal = terminalOutcome(association);
        if (terminal != null) {
            return terminal;
        }

        UUID claimToken = UUID.randomUUID();
        long leaseMillis = positive(properties.getMapping().getClaimLeaseMillis(), 30_000L);
        if (!store.claim(tenantId, meetingId, sourceSessionId,
                claimToken, now, now.plusMillis(leaseMillis))) {
            return Outcome.pending("MAPPING_RESOLUTION_IN_PROGRESS");
        }

        MeetingSessionResolution resolution = resolver.resolve(
                tenantId, meetingId, sourceSessionId);
        if (resolution.status() == MeetingSessionResolution.Status.RESOLVED) {
            if (!scopeMatches(resolution, tenantId, meetingId, sourceSessionId)) {
                store.fail(association.getId(), claimToken, "MAPPING_SCOPE_MISMATCH",
                        maxAttempts(), true, null, clock.instant());
                meters.counter("transcript_session_mapping_dead_total", "reason", "scope_mismatch")
                        .increment();
                return terminalOrPending(store.require(tenantId, meetingId, sourceSessionId));
            }
            boolean completed;
            try {
                completed = store.completeResolved(
                        association.getId(), claimToken, tenantId, meetingId,
                        sourceSessionId, resolution.sessionId(), clock.instant());
            } catch (SessionAssociationConflictException | DataIntegrityViolationException conflict) {
                store.fail(association.getId(), claimToken, "SEGMENT_SESSION_CONFLICT",
                        maxAttempts(), true, null, clock.instant());
                meters.counter("transcript_session_mapping_dead_total", "reason", "segment_conflict")
                        .increment();
                return terminalOrPending(store.require(tenantId, meetingId, sourceSessionId));
            }
            if (!completed) {
                return terminalOrPending(store.require(tenantId, meetingId, sourceSessionId));
            }
            meters.counter("transcript_session_mapping_resolved_total").increment();
            return Outcome.resolved(resolution.sessionId());
        }

        boolean forceDead = resolution.status() == MeetingSessionResolution.Status.INVALID;
        Instant failedAt = clock.instant();
        long backoff = backoffMillis(association.getResolutionAttempts() + 1);
        store.fail(association.getId(), claimToken, boundedCode(resolution.errorCode()),
                maxAttempts(), forceDead, failedAt.plusMillis(backoff), failedAt);
        TranscriptSessionAssociation updated = store.require(tenantId, meetingId, sourceSessionId);
        if (updated.getStatus() == TranscriptSessionAssociationStatus.DEAD) {
            meters.counter("transcript_session_mapping_dead_total", "reason", "attempts_exhausted")
                    .increment();
            return Outcome.dead(updated.getLastErrorCode());
        }
        meters.counter("transcript_session_mapping_retry_total", "reason", boundedCode(resolution.errorCode()))
                .increment();
        return Outcome.pending(updated.getLastErrorCode());
    }

    private Outcome terminalOutcome(TranscriptSessionAssociation association) {
        if (association.getStatus() == TranscriptSessionAssociationStatus.RESOLVED) {
            if (association.getSessionId() == null) {
                return Outcome.dead("RESOLVED_MAPPING_WITHOUT_SESSION");
            }
            return Outcome.resolved(association.getSessionId());
        }
        if (association.getStatus() == TranscriptSessionAssociationStatus.DEAD) {
            return Outcome.dead(association.getLastErrorCode());
        }
        return null;
    }

    private Outcome terminalOrPending(TranscriptSessionAssociation association) {
        Outcome terminal = terminalOutcome(association);
        if (terminal != null) {
            return terminal;
        }
        String reason = association.getLastErrorCode();
        return Outcome.pending(reason == null || reason.isBlank()
                ? "MAPPING_RESOLUTION_IN_PROGRESS" : reason);
    }

    private boolean scopeMatches(
            MeetingSessionResolution resolution,
            UUID tenantId,
            UUID meetingId,
            String sourceSessionId) {
        return resolution.sessionId() != null
                && tenantId.equals(resolution.tenantId())
                && tenantId.equals(resolution.orgId())
                && meetingId.equals(resolution.meetingId())
                && sourceSessionId.equals(resolution.sourceSessionId());
    }

    private int maxAttempts() {
        return Math.max(1, properties.getMapping().getMaxAttempts());
    }

    private long backoffMillis(int attempt) {
        long initial = positive(properties.getMapping().getInitialBackoffMillis(), 5_000L);
        long maximum = Math.max(initial,
                positive(properties.getMapping().getMaxBackoffMillis(), 300_000L));
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        if (initial > Long.MAX_VALUE / multiplier) {
            return maximum;
        }
        return Math.min(initial * multiplier, maximum);
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private static String boundedCode(String code) {
        if (code == null || code.isBlank()) {
            return "MAPPING_UNAVAILABLE";
        }
        String normalized = code.replaceAll("[^A-Z0-9_]", "_");
        return normalized.substring(0, Math.min(normalized.length(), 64));
    }

    private static UUID deterministicId(UUID tenantId, UUID meetingId, String sourceSessionId) {
        String key = tenantId + "|" + meetingId + "|"
                + DirectSttTranscriptResultEvent.SOURCE_SYSTEM + "|" + sourceSessionId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public record Outcome(Result result, UUID sessionId, String reasonCode) {
        static Outcome resolved(UUID sessionId) {
            return new Outcome(Result.RESOLVED, sessionId, null);
        }

        static Outcome pending(String reasonCode) {
            return new Outcome(Result.PENDING, null, reasonCode);
        }

        static Outcome dead(String reasonCode) {
            return new Outcome(Result.DEAD, null, reasonCode);
        }
    }

    public enum Result {
        RESOLVED,
        PENDING,
        DEAD
    }
}
