package com.example.transcript.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Entity-level unit tests for {@link TranscriptSegment} and
 * {@link TranscriptAccessAudit} — the effective-org accessor + lifecycle
 * defaults, no Spring context.
 */
class TranscriptSegmentTest {

    @Test
    void effectiveOrgId_prefersOrgId_whenPopulated() {
        UUID tenant = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        TranscriptSegment segment = new TranscriptSegment();
        segment.setTenantId(tenant);
        segment.setOrgId(org);

        assertThat(segment.getEffectiveOrgId()).isEqualTo(org);
    }

    @Test
    void effectiveOrgId_fallsBackToTenantId_whenOrgIdNull() {
        UUID tenant = UUID.randomUUID();
        TranscriptSegment segment = new TranscriptSegment();
        segment.setTenantId(tenant);
        segment.setOrgId(null);

        assertThat(segment.getEffectiveOrgId()).isEqualTo(tenant);
    }

    @Test
    void status_defaultsToDraft() {
        TranscriptSegment segment = new TranscriptSegment();
        assertThat(segment.getStatus()).isEqualTo(TranscriptSegmentStatus.DRAFT);
    }

    @Test
    void prePersist_setsTimestampsAndDefaultStatus() {
        TranscriptSegment segment = new TranscriptSegment();
        segment.setStatus(null);
        segment.prePersist();

        assertThat(segment.getCreatedAt()).isNotNull();
        assertThat(segment.getUpdatedAt()).isNotNull();
        assertThat(segment.getStatus()).isEqualTo(TranscriptSegmentStatus.DRAFT);
    }

    @Test
    void accessAudit_effectiveOrgId_fallsBackToTenantId() {
        UUID tenant = UUID.randomUUID();
        TranscriptAccessAudit audit = new TranscriptAccessAudit();
        audit.setTenantId(tenant);

        assertThat(audit.getEffectiveOrgId()).isEqualTo(tenant);
    }

    @Test
    void accessAudit_prePersist_setsAccessedAt() {
        TranscriptAccessAudit audit = new TranscriptAccessAudit();
        audit.prePersist();
        assertThat(audit.getAccessedAt()).isNotNull();
    }

    @Test
    void accessAudit_hasNoTranscriptTextOrSearchTermFields() {
        // Structural KVKK guard: the audit entity must NEVER carry transcript
        // text or a search term. If a future change adds such a field this test
        // fails the build (the reflective field-name check is the canary).
        java.util.Set<String> fieldNames = new java.util.HashSet<>();
        for (var f : TranscriptAccessAudit.class.getDeclaredFields()) {
            fieldNames.add(f.getName().toLowerCase(java.util.Locale.ROOT));
        }
        assertThat(fieldNames)
                .as("transcript_access_audit must stay TRANSCRIPT-FREE")
                .doesNotContain("text", "textdraft", "textfinal", "transcript",
                        "query", "searchterm", "term", "content");
    }
}
