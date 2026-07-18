package com.example.meeting.service;

import com.example.meeting.model.MeetingIntelligenceResultAccessAudit;
import com.example.meeting.model.MeetingIntelligenceResultAccessType;
import com.example.meeting.repository.MeetingIntelligenceResultAccessAuditRepository;
import com.example.meeting.security.AdminTenantContext;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Writes fail-closed, metadata-only audit evidence for successful result reads. */
@Service
public class MeetingIntelligenceResultAccessAuditService {

    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{16,64}$");

    private final MeetingIntelligenceResultAccessAuditRepository repository;

    public MeetingIntelligenceResultAccessAuditService(
            MeetingIntelligenceResultAccessAuditRepository repository) {
        this.repository = repository;
    }

    public MeetingIntelligenceResultAccessAudit recordCanonicalRead(
            AdminTenantContext context,
            UUID meetingId,
            UUID analysisRunId) {
        return record(context, meetingId, analysisRunId,
                MeetingIntelligenceResultAccessType.CANONICAL_RESULT_READ);
    }

    public MeetingIntelligenceResultAccessAudit recordCanonicalTranscriptRead(
            AdminTenantContext context,
            UUID meetingId,
            UUID analysisRunId) {
        return record(context, meetingId, analysisRunId,
                MeetingIntelligenceResultAccessType.CANONICAL_TRANSCRIPT_READ);
    }

    private MeetingIntelligenceResultAccessAudit record(
            AdminTenantContext context,
            UUID meetingId,
            UUID analysisRunId,
            MeetingIntelligenceResultAccessType accessType) {
        UUID effectiveOrgId = context.tenantId();
        MeetingIntelligenceResultAccessAudit audit = new MeetingIntelligenceResultAccessAudit();
        // The resolved context is authoritative even for legacy parent rows with org_id NULL.
        audit.setTenantId(effectiveOrgId);
        audit.setOrgId(effectiveOrgId);
        audit.setAccessorSubject(context.subject());
        audit.setMeetingId(meetingId);
        audit.setAnalysisRunId(analysisRunId);
        audit.setAccessType(accessType);
        audit.setResultCount(1);
        audit.setTraceId(allowlistedTraceId(MDC.get("traceId")));
        audit.setAccessedAt(Instant.now());
        return repository.saveAndFlush(audit);
    }

    static String allowlistedTraceId(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        return TRACE_ID.matcher(normalized).matches() ? normalized : null;
    }
}
