package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.meeting.model.MeetingIntelligenceResultAccessAudit;
import com.example.meeting.model.MeetingIntelligenceResultAccessType;
import com.example.meeting.repository.MeetingIntelligenceResultAccessAuditRepository;
import com.example.meeting.security.AdminTenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class MeetingIntelligenceResultAccessAuditServiceTest {

    private static final UUID ORG_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RUN_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Mock
    private MeetingIntelligenceResultAccessAuditRepository repository;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void recordCanonicalRead_usesEffectiveContextAndSubjectNotAuthzPrincipal() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MDC.put("traceId", "ABCDEF0123456789ABCDEF0123456789");
        AdminTenantContext context =
                new AdminTenantContext(ORG_ID, "audit@example.com", "fga-user-42");
        MeetingIntelligenceResultAccessAuditService service =
                new MeetingIntelligenceResultAccessAuditService(repository);

        service.recordCanonicalRead(context, MEETING_ID, RUN_ID);

        ArgumentCaptor<MeetingIntelligenceResultAccessAudit> captor =
                ArgumentCaptor.forClass(MeetingIntelligenceResultAccessAudit.class);
        verify(repository).saveAndFlush(captor.capture());
        MeetingIntelligenceResultAccessAudit audit = captor.getValue();
        assertThat(audit.getTenantId()).isEqualTo(ORG_ID);
        assertThat(audit.getOrgId()).isEqualTo(ORG_ID);
        assertThat(audit.getAccessorSubject()).isEqualTo("audit@example.com");
        assertThat(audit.getAccessorSubject()).isNotEqualTo("fga-user-42");
        assertThat(audit.getMeetingId()).isEqualTo(MEETING_ID);
        assertThat(audit.getAnalysisRunId()).isEqualTo(RUN_ID);
        assertThat(audit.getAccessType())
                .isEqualTo(MeetingIntelligenceResultAccessType.CANONICAL_RESULT_READ);
        assertThat(audit.getResultCount()).isEqualTo(1);
        assertThat(audit.getTraceId()).isEqualTo("abcdef0123456789abcdef0123456789");
        assertThat(audit.getAccessedAt()).isNotNull();
    }

    @Test
    void allowlistedTraceId_discardsMalformedOptionalMetadata() {
        assertThat(MeetingIntelligenceResultAccessAuditService.allowlistedTraceId(
                "not-a-trace;drop table")).isNull();
        assertThat(MeetingIntelligenceResultAccessAuditService.allowlistedTraceId("abc"))
                .isNull();
        assertThat(MeetingIntelligenceResultAccessAuditService.allowlistedTraceId(null))
                .isNull();
    }
}
