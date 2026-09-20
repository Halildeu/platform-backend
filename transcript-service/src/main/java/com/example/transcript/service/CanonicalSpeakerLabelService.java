package com.example.transcript.service;

import com.example.common.meeting.speakers.SpeakerLabels;
import com.example.transcript.finalization.FinalizedTranscriptSnapshotCodec;
import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptSessionErasureStatus;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSessionErasureTombstoneRepository;
import com.example.transcript.security.AdminTenantContext;
import com.example.transcript.security.AnalysisSpecVersionPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** One transaction: session fence, occurrence lock, revision check, label change and metadata audit. */
@Service
public class CanonicalSpeakerLabelService {
    private final TranscriptFinalizationRepository finalizations;
    private final TranscriptSessionErasureTombstoneRepository tombstones;
    private final SessionErasureFence fence;
    private final FinalizedTranscriptSnapshotCodec codec;
    private final AnalysisSpecVersionPolicy specs;
    private final TranscriptAccessAuditService audit;
    private final ObjectMapper mapper;

    public CanonicalSpeakerLabelService(TranscriptFinalizationRepository finalizations,
            TranscriptSessionErasureTombstoneRepository tombstones, SessionErasureFence fence,
            FinalizedTranscriptSnapshotCodec codec, AnalysisSpecVersionPolicy specs,
            TranscriptAccessAuditService audit, ObjectMapper mapper) {
        this.finalizations = finalizations; this.tombstones = tombstones; this.fence = fence;
        this.codec = codec; this.specs = specs; this.audit = audit; this.mapper = mapper;
    }

    @Transactional
    public SpeakerLabels.Snapshot read(UUID tenant, UUID meeting, UUID session, long version,
            UUID requestedTenant, UUID run, String spec, String actor) {
        var f = locked(tenant, meeting, session, version, requestedTenant, run, spec, actor, false);
        var known = knownSpeakers(f);
        var labels = labels(f, known);
        audit.recordSpeakerLabels(new AdminTenantContext(tenant, actor, actor), meeting, session, labels.size(), false);
        return snapshot(f, labels, known);
    }

    @Transactional
    public SpeakerLabels.Snapshot edit(UUID tenant, UUID meeting, UUID session, long version,
            UUID requestedTenant, UUID run, String spec, String actor, SpeakerLabels.Edit edit) {
        var f = locked(tenant, meeting, session, version, requestedTenant, run, spec, actor, true);
        var known = knownSpeakers(f);
        if (edit == null || !known.contains(edit.key())) throw status(HttpStatus.BAD_REQUEST, "LABEL_SPEAKER_UNAVAILABLE");
        if (edit.expectedRevision() != f.getSpeakerLabelsRevision())
            throw status(HttpStatus.CONFLICT, "LABEL_REVISION_CONFLICT");
        var labels = new ArrayList<>(labels(f, known));
        labels.removeIf(label -> label.key().equals(edit.key()));
        if (edit.name() != null) labels.add(new SpeakerLabels.Label(edit.scope(), edit.speaker(), edit.name()));
        if (labels.size() > SpeakerLabels.MAX_LABELS) throw status(HttpStatus.BAD_REQUEST, "LABEL_LIMIT_EXCEEDED");
        if (f.getSpeakerLabelsRevision() >= 9007199254740991L) throw status(HttpStatus.CONFLICT, "LABEL_REVISION_EXHAUSTED");
        try {
            String encoded = mapper.writeValueAsString(labels);
            if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 131072)
                throw status(HttpStatus.BAD_REQUEST, "LABEL_LIMIT_EXCEEDED");
            f.setSpeakerLabels(encoded);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("LABEL_ENCODING_FAILED"); }
        if (finalizations.updateSpeakerLabels(f.getId(), tenant, f.getSpeakerLabelsRevision(), f.getSpeakerLabels()) != 1)
            throw status(HttpStatus.CONFLICT, "LABEL_REVISION_CONFLICT");
        f.setSpeakerLabelsRevision(f.getSpeakerLabelsRevision() + 1);
        // Neither old nor new names enter the audit. Failure rolls back the label update.
        audit.recordSpeakerLabels(new AdminTenantContext(tenant, actor, actor), meeting, session, 1, true);
        return snapshot(f, labels, known);
    }

    private TranscriptFinalization locked(UUID tenant, UUID meeting, UUID session, long version,
            UUID requestedTenant, UUID run, String spec, String actor, boolean write) {
        if (version < 1) throw status(HttpStatus.BAD_REQUEST, "FINALIZATION_VERSION_INVALID");
        if (!tenant.equals(requestedTenant)) throw status(HttpStatus.FORBIDDEN, "TENANT_SCOPE_MISMATCH");
        if (actor == null || actor.isBlank() || actor.length() > 255
                || actor.codePoints().anyMatch(Character::isISOControl)) throw status(HttpStatus.BAD_REQUEST, "LABEL_ACTOR_INVALID");
        specs.requireAllowed(spec);
        fence.lock(SessionErasureFence.canonicalKey(new SessionErasureFence.UUIDScope(tenant, meeting, session)));
        var f = finalizations.findVisibleAnalysisOccurrenceForUpdate(tenant, meeting, session, version, run);
        var tombstone = tombstones.findByTenantIdAndMeetingIdAndSessionId(tenant, meeting, session);
        if (tombstone.filter(t -> t.getStatus() == TranscriptSessionErasureStatus.COMPLETE).isPresent())
            throw status(HttpStatus.GONE, "TRANSCRIPT_ERASED");
        if (tombstone.isPresent() && (write || f.isEmpty() || !f.get().isLegalHold()))
            throw status(HttpStatus.LOCKED, "TRANSCRIPT_ERASURE_PENDING");
        var result = f.orElseThrow(() -> status(HttpStatus.NOT_FOUND, "FINALIZATION_NOT_FOUND"));
        if (write && result.isLegalHold()) throw status(HttpStatus.LOCKED, "TRANSCRIPT_LEGAL_HOLD");
        return result;
    }

    private Set<String> knownSpeakers(TranscriptFinalization f) {
        Set<String> result = new HashSet<>();
        try {
            if (!codec.hasPersistedProjection(f))
                throw status(HttpStatus.CONFLICT, "LABEL_ATTRIBUTION_UNAVAILABLE");
            for (var segment : codec.restore(f).segments()) {
                var attribution = segment.speakerAttribution();
                if (attribution == null) continue;
                for (var turn : attribution.turns()) if (!"UU".equals(turn.speaker()))
                    result.add(attribution.scope() + ":" + turn.speaker());
            }
        } catch (FinalizedTranscriptSnapshotCodec.InvalidStoredSnapshotException ex) {
            throw status(HttpStatus.CONFLICT, "FINALIZATION_INTEGRITY_MISMATCH");
        }
        return result;
    }

    private List<SpeakerLabels.Label> labels(TranscriptFinalization f, Set<String> known) {
        try {
            String raw = f.getSpeakerLabels();
            if (raw == null || raw.length() > 131072
                    || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 131072) throw new IllegalArgumentException();
            List<SpeakerLabels.Label> labels = mapper.readValue(raw, new TypeReference<>() { });
            if (labels == null || labels.size() > SpeakerLabels.MAX_LABELS
                    || labels.stream().anyMatch(label -> label == null || !known.contains(label.key()))
                    || labels.stream().map(SpeakerLabels.Label::key).distinct().count() != labels.size())
                throw new IllegalArgumentException();
            return List.copyOf(labels);
        } catch (Exception invalid) { throw status(HttpStatus.CONFLICT, "LABEL_DATA_INVALID"); }
    }

    private SpeakerLabels.Snapshot snapshot(TranscriptFinalization f, List<SpeakerLabels.Label> labels, Set<String> known) {
        return new SpeakerLabels.Snapshot(f.getTenantId(), f.getMeetingId(), f.getSessionId(),
                f.getFinalizationVersion(), f.getAnalysisRunId(), f.getCanonicalTranscriptSha256(),
                f.getSpeakerLabelsRevision(), !f.isLegalHold() && !known.isEmpty(), labels);
    }
    private static ResponseStatusException status(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }
}
