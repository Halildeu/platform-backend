package com.example.transcript.service;

import com.example.transcript.dto.CreateTranscriptSegmentRequest;
import com.example.transcript.dto.TranscriptSegmentDto;
import com.example.transcript.dto.UpdateTranscriptSegmentRequest;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSegmentMutationScope;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import com.example.transcript.security.AdminTenantContext;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Transcript segment CRUD + search + export.
 *
 * <p><b>KVKK m.12 contract:</b> every method that READS / LISTS / SEARCHES /
 * EXPORTS transcript personal data writes a {@code transcript_access_audit} row
 * AFTER serving the request, via {@link TranscriptAccessAuditService}. The
 * accessor is the resolved {@link AdminTenantContext} principal (never request
 * body). Mutations (create/update/delete) do NOT write access-audit rows —
 * those are not "access to existing personal data" in the KVKK m.12 read sense
 * (a separate change/audit layer covers writes; #52 3-AI audit 2-katman ayrımı).
 *
 * <p><b>Tenant scope:</b> all reads use the repository effective-org predicate;
 * all writes set BOTH {@code tenantId} and {@code orgId} to the same UUID
 * (canonical org_id write).
 */
@Service
public class TranscriptSegmentService {

    private final TranscriptSegmentRepository repository;
    private final TranscriptSessionAssociationRepository associationRepository;
    private final TranscriptAccessAuditService accessAuditService;
    private final SessionErasureFence erasureFence;
    private final int defaultPageSize;
    private final int maxPageSize;
    private final int exportMaxRows;

    public TranscriptSegmentService(
            TranscriptSegmentRepository repository,
            TranscriptSessionAssociationRepository associationRepository,
            TranscriptAccessAuditService accessAuditService,
            SessionErasureFence erasureFence,
            @Value("${transcript.search.default-page-size:50}") int defaultPageSize,
            @Value("${transcript.search.max-page-size:200}") int maxPageSize,
            @Value("${transcript.export.max-rows:50000}") int exportMaxRows) {
        this.repository = repository;
        this.associationRepository = associationRepository;
        this.accessAuditService = accessAuditService;
        this.erasureFence = erasureFence;
        this.defaultPageSize = defaultPageSize;
        this.maxPageSize = maxPageSize;
        this.exportMaxRows = exportMaxRows;
    }

    // ───────────────────────────── CREATE ─────────────────────────────

    @Transactional
    public TranscriptSegmentDto create(AdminTenantContext context, CreateTranscriptSegmentRequest request) {
        if (request.startTime() != null && request.endTime() != null
                && request.endTime() < request.startTime()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "endTime must be greater than or equal to startTime.");
        }
        UUID tenantId = context.tenantId();
        lockCanonicalSessionForMutation(tenantId, request.meetingId(), request.sessionId());

        TranscriptSegment segment = new TranscriptSegment();
        // Canonical org_id write: set BOTH columns to the same tenant UUID.
        segment.setTenantId(tenantId);
        segment.setOrgId(tenantId);
        // Segment create is tenant-scoped (tenantId is stamped from the
        // authenticated context, never from the request body). meetingId is a
        // producer-supplied reference: cross-service meeting parent
        // existence/ownership verification is DEFERRED (no meeting-service
        // client in v1; the absence of a cross-service FK is deliberate —
        // meeting-service owns a separate schema). This is NOT a "parent-gate
        // 404-no-leak" guarantee for create — that property holds for READS
        // (tenant-gated, see the repository effective-org predicate); on create
        // we are tenant-scoped + producer-trusted. A meetingId belonging to
        // another tenant would only ever become a dangling reference that is
        // never joinable across tenants on read (no cross-tenant leak). The
        // @NotNull on CreateTranscriptSegmentRequest.meetingId is the defensive
        // floor (reject a null reference outright).
        segment.setMeetingId(request.meetingId());
        segment.setSessionId(request.sessionId());
        segment.setSpeakerId(request.speakerId());
        segment.setStartTime(request.startTime());
        segment.setEndTime(request.endTime());
        segment.setTextDraft(request.textDraft());
        segment.setTextFinal(request.textFinal());
        segment.setConfidence(request.confidence());
        segment.setStatus(request.status() != null ? request.status() : TranscriptSegmentStatus.DRAFT);
        return TranscriptSegmentDto.from(repository.saveAndFlush(segment));
    }

    // ────────────────────────────── READ ──────────────────────────────

    /**
     * READ a single segment by id. Writes a KVKK m.12 READ access-audit row on
     * success. 404 (no existence leak) when the segment is not visible to the
     * caller's tenant.
     */
    @Transactional
    public TranscriptSegmentDto getSegment(AdminTenantContext context, UUID segmentId) {
        TranscriptSegment segment = repository.findVisibleToOrgAndId(context.tenantId(), segmentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Transcript segment not found."));
        // KVKK m.12: record the read AFTER the segment is resolved/authorized.
        accessAuditService.recordRead(context, segment.getId(), segment.getMeetingId(), segment.getSessionId());
        return TranscriptSegmentDto.from(segment);
    }

    // ────────────────────────────── LIST ──────────────────────────────

    /**
     * LIST a meeting's segments (paged). Writes a KVKK m.12 LIST access-audit
     * row with the returned-row count.
     */
    @Transactional
    public Page<TranscriptSegmentDto> listByMeeting(AdminTenantContext context, UUID meetingId,
                                                    int page, int size) {
        Pageable pageable = pageable(page, size);
        Page<TranscriptSegment> result = repository.findVisibleToOrgByMeeting(
                context.tenantId(), meetingId, pageable);
        accessAuditService.recordList(context, meetingId, null, result.getNumberOfElements());
        return result.map(TranscriptSegmentDto::from);
    }

    /**
     * LIST a session's segments (paged). Writes a KVKK m.12 LIST access-audit
     * row with the returned-row count.
     */
    @Transactional
    public Page<TranscriptSegmentDto> listBySession(AdminTenantContext context, UUID sessionId,
                                                    int page, int size) {
        Pageable pageable = pageable(page, size);
        Page<TranscriptSegment> result = repository.findVisibleToOrgBySession(
                context.tenantId(), sessionId, pageable);
        accessAuditService.recordList(context, null, sessionId, result.getNumberOfElements());
        return result.map(TranscriptSegmentDto::from);
    }

    // ───────────────────────────── SEARCH ─────────────────────────────

    /**
     * SEARCH segment text (draft + final), case-insensitive substring.
     * Optionally scoped to a single meeting. Writes a KVKK m.12 SEARCH
     * access-audit row with the matched-row count — the search TERM is NEVER
     * persisted (transcript-free audit).
     */
    @Transactional
    public Page<TranscriptSegmentDto> search(AdminTenantContext context, String query,
                                             UUID meetingId, int page, int size) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Search query must not be blank.");
        }
        String term = query.trim().toLowerCase(Locale.ROOT);
        Pageable pageable = pageable(page, size);
        Page<TranscriptSegment> result = repository.searchVisibleToOrg(
                context.tenantId(), meetingId, term, pageable);
        // KVKK m.12: record the SEARCH with the count only (NO term stored).
        accessAuditService.recordSearch(context, meetingId, result.getNumberOfElements());
        return result.map(TranscriptSegmentDto::from);
    }

    // ───────────────────────────── EXPORT ─────────────────────────────

    /**
     * EXPORT a meeting's segments (capped at {@code transcript.export.max-rows}).
     * Returns the materialized DTO list; the controller renders it as CSV or
     * JSON. Writes a KVKK m.12 EXPORT access-audit row with the exported-row
     * count.
     */
    @Transactional
    public List<TranscriptSegmentDto> exportByMeeting(AdminTenantContext context, UUID meetingId) {
        // Cap +1 so we can detect "too many to export" and fail-closed rather
        // than silently truncating a privacy-sensitive export.
        Pageable cap = PageRequest.of(0, exportMaxRows + 1);
        List<TranscriptSegment> segments = repository.findAllVisibleToOrgByMeeting(
                context.tenantId(), meetingId, cap);
        if (segments.size() > exportMaxRows) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Export exceeds the maximum of " + exportMaxRows
                            + " segments; narrow the scope.");
        }
        List<TranscriptSegmentDto> dtos = segments.stream()
                .map(TranscriptSegmentDto::from)
                .toList();
        accessAuditService.recordExport(context, meetingId, dtos.size());
        return dtos;
    }

    // ───────────────────────────── UPDATE ─────────────────────────────

    /**
     * Partial update. When {@code expectedVersion} is supplied it is the
     * optimistic-lock precondition: a mismatch throws
     * {@link OptimisticLockingFailureException} (→ 409) BEFORE the mutation is
     * applied. Even without it, JPA {@code @Version} guards concurrent writers.
     * A {@code null} request field leaves the existing value unchanged.
     */
    @Transactional
    public TranscriptSegmentDto update(AdminTenantContext context, UUID segmentId,
                                       UpdateTranscriptSegmentRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Update request is required.");
        }
        TranscriptSegment segment = lockMutableSegment(context.tenantId(), segmentId);

        // Optimistic-lock precondition: reject on mismatch BEFORE mutating.
        if (request.expectedVersion() != null
                && !request.expectedVersion().equals(segment.getVersion())) {
            throw new OptimisticLockingFailureException(
                    "Transcript segment version mismatch: expected " + request.expectedVersion()
                            + " but found " + segment.getVersion() + ".");
        }

        Double newStart = request.startTime() != null ? request.startTime() : segment.getStartTime();
        Double newEnd = request.endTime() != null ? request.endTime() : segment.getEndTime();
        if (newStart != null && newEnd != null && newEnd < newStart) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "endTime must be greater than or equal to startTime.");
        }

        if (request.startTime() != null) {
            segment.setStartTime(request.startTime());
        }
        if (request.endTime() != null) {
            segment.setEndTime(request.endTime());
        }
        if (request.textDraft() != null) {
            segment.setTextDraft(request.textDraft());
        }
        if (request.textFinal() != null) {
            segment.setTextFinal(request.textFinal());
        }
        if (request.confidence() != null) {
            segment.setConfidence(request.confidence());
        }
        if (request.status() != null) {
            segment.setStatus(request.status());
        }
        if (request.speakerId() != null) {
            segment.setSpeakerId(request.speakerId());
        }
        return TranscriptSegmentDto.from(repository.saveAndFlush(segment));
    }

    // ───────────────────────────── DELETE ─────────────────────────────

    @Transactional
    public void delete(AdminTenantContext context, UUID segmentId) {
        TranscriptSegment segment = lockMutableSegment(context.tenantId(), segmentId);
        repository.delete(segment);
    }

    // ───────────────────────────── helpers ────────────────────────────

    private TranscriptSegment lockMutableSegment(UUID tenantId, UUID segmentId) {
        TranscriptSegmentMutationScope scope = repository
                .findVisibleMutationScope(tenantId, segmentId)
                .orElseThrow(() -> new ResponseStatusException(
                        NOT_FOUND, "Transcript segment not found."));

        lockCanonicalSessionForMutation(tenantId, scope.meetingId(), scope.sessionId());

        TranscriptSegment segment = repository
                .findVisibleToOrgAndIdForUpdate(tenantId, segmentId)
                .orElseThrow(() -> new ResponseStatusException(
                        NOT_FOUND, "Transcript segment not found."));
        if (!scope.meetingId().equals(segment.getMeetingId())
                || !Objects.equals(scope.sessionId(), segment.getSessionId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Transcript segment session changed concurrently; retry the mutation.");
        }
        return segment;
    }

    /**
     * Finalization and every canonical segment writer take this association
     * lock before locking segment rows. Once an immutable occurrence exists,
     * admin CRUD must not rewrite the occurrence in place.
     */
    private void lockCanonicalSessionForMutation(
            UUID tenantId, UUID meetingId, UUID sessionId) {
        if (sessionId == null) {
            return;
        }
        lockErasureFence(tenantId, meetingId, sessionId);
        TranscriptSessionAssociation association = associationRepository
                .findCanonicalForUpdate(tenantId, meetingId, sessionId)
                .orElse(null);
        if (association != null && association.getFinalizationVersion() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Finalized canonical transcript segments are immutable.");
        }
    }

    private void lockErasureFence(UUID tenantId, UUID meetingId, UUID sessionId) {
        if (sessionId == null) {
            return;
        }
        SessionErasureFence.UUIDScope scope =
                new SessionErasureFence.UUIDScope(tenantId, meetingId, sessionId);
        erasureFence.lock(SessionErasureFence.canonicalKey(scope));
        erasureFence.rejectErased(scope, null);
    }

    private Pageable pageable(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? defaultPageSize : Math.min(size, maxPageSize);
        return PageRequest.of(safePage, safeSize);
    }
}
