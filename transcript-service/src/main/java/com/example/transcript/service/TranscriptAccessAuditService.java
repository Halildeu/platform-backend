package com.example.transcript.service;

import com.example.transcript.model.TranscriptAccessAudit;
import com.example.transcript.model.TranscriptAccessType;
import com.example.transcript.repository.TranscriptAccessAuditRepository;
import com.example.transcript.security.AdminTenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Writes KVKK Madde 12 access-log rows.
 *
 * <p>This is the single chokepoint for the "audit every access" requirement.
 * The segment service calls {@link #recordRead}, {@link #recordList},
 * {@link #recordSearch}, {@link #recordExport} AFTER serving each read/list/
 * search/export of transcript personal data.
 *
 * <p><b>TRANSCRIPT-FREE invariant.</b> None of these methods accept the segment
 * text or the search term — only metadata (ids, counts, type). The
 * {@link TranscriptAccessAudit} entity has no field to hold such content, so
 * the invariant is enforced structurally, not by discipline.
 *
 * <p>The accessor is taken from the resolved {@link AdminTenantContext}
 * (request authz principal), so the "kim" recorded is the gate principal — it
 * can never be spoofed via a request body.
 *
 * <p>Canonical write: both {@code tenantId} and {@code orgId} are set to the
 * same tenant UUID (so the V1 CHECK passes without relying on the trigger).
 */
@Service
public class TranscriptAccessAuditService {

    private final TranscriptAccessAuditRepository repository;

    public TranscriptAccessAuditService(TranscriptAccessAuditRepository repository) {
        this.repository = repository;
    }

    /** Single-segment READ. */
    public TranscriptAccessAudit recordRead(AdminTenantContext context,
                                            UUID segmentId,
                                            UUID meetingId,
                                            UUID sessionId) {
        return write(context, TranscriptAccessType.READ, segmentId, meetingId, sessionId, null);
    }

    /** Meeting/session LIST — {@code resultCount} rows returned. */
    public TranscriptAccessAudit recordList(AdminTenantContext context,
                                            UUID meetingId,
                                            UUID sessionId,
                                            int resultCount) {
        return write(context, TranscriptAccessType.LIST, null, meetingId, sessionId, resultCount);
    }

    /**
     * SEARCH — {@code resultCount} rows matched. The search TERM is NEVER
     * passed in (and there is no column for it); only the count + the optional
     * meeting scope are recorded.
     */
    public TranscriptAccessAudit recordSearch(AdminTenantContext context,
                                              UUID meetingId,
                                              int resultCount) {
        return write(context, TranscriptAccessType.SEARCH, null, meetingId, null, resultCount);
    }

    /** EXPORT — {@code resultCount} rows exported (CSV/JSON). */
    public TranscriptAccessAudit recordExport(AdminTenantContext context,
                                              UUID meetingId,
                                              int resultCount) {
        return write(context, TranscriptAccessType.EXPORT, null, meetingId, null, resultCount);
    }

    private TranscriptAccessAudit write(AdminTenantContext context,
                                        TranscriptAccessType accessType,
                                        UUID segmentId,
                                        UUID meetingId,
                                        UUID sessionId,
                                        Integer resultCount) {
        UUID tenantId = context.tenantId();
        TranscriptAccessAudit audit = new TranscriptAccessAudit();
        // Canonical org_id write: set BOTH columns to the same tenant UUID.
        audit.setTenantId(tenantId);
        audit.setOrgId(tenantId);
        audit.setAccessorSubject(context.subject());
        audit.setAccessType(accessType);
        audit.setSegmentId(segmentId);
        audit.setMeetingId(meetingId);
        audit.setSessionId(sessionId);
        audit.setResultCount(resultCount);
        audit.setAccessedAt(Instant.now());
        return repository.save(audit);
    }
}
