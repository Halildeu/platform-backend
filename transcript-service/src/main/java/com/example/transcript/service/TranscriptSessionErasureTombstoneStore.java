package com.example.transcript.service;

import com.example.transcript.model.TranscriptSessionErasureStatus;
import com.example.transcript.model.TranscriptSessionErasureTombstone;
import com.example.transcript.repository.TranscriptSessionErasureTombstoneRepository;
import com.example.transcript.service.SessionErasureFence.UUIDScope;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Commits the permanent admission fence before destructive erasure work starts. */
@Component
public class TranscriptSessionErasureTombstoneStore {

    private final TranscriptSessionErasureTombstoneRepository tombstones;
    private final SessionErasureFence fence;
    private final Clock clock;

    @Autowired
    public TranscriptSessionErasureTombstoneStore(
            TranscriptSessionErasureTombstoneRepository tombstones,
            SessionErasureFence fence) {
        this(tombstones, fence, Clock.systemUTC());
    }

    TranscriptSessionErasureTombstoneStore(
            TranscriptSessionErasureTombstoneRepository tombstones,
            SessionErasureFence fence,
            Clock clock) {
        this.tombstones = tombstones;
        this.fence = fence;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void observe(UUIDScope scope, String sourceSessionId) {
        fence.lock(
                SessionErasureFence.canonicalKey(scope),
                SessionErasureFence.sourceKey(
                        scope.tenantId(), scope.meetingId(), sourceSessionId));
        TranscriptSessionErasureTombstone row = tombstones.findSessionForUpdate(
                        scope.tenantId(), scope.meetingId(), scope.sessionId())
                .orElseGet(() -> create(scope, sourceSessionId));
        String sourceHash = SessionErasureFence.sourceHash(sourceSessionId);
        if (row.getSourceSessionHash() != null && sourceHash != null
                && !row.getSourceSessionHash().equals(sourceHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ERASURE_SOURCE_SCOPE_MISMATCH");
        }
    }

    private TranscriptSessionErasureTombstone create(UUIDScope scope, String sourceSessionId) {
        Instant now = clock.instant();
        TranscriptSessionErasureTombstone row = new TranscriptSessionErasureTombstone();
        row.setId(UUID.randomUUID());
        row.setTenantId(scope.tenantId());
        row.setOrgId(scope.tenantId());
        row.setMeetingId(scope.meetingId());
        row.setSessionId(scope.sessionId());
        row.setSourceSessionHash(SessionErasureFence.sourceHash(sourceSessionId));
        row.setStatus(TranscriptSessionErasureStatus.HELD);
        row.setRequestedAt(now);
        row.setUpdatedAt(now);
        return tombstones.saveAndFlush(row);
    }
}
