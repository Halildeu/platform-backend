package com.example.transcript.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.testsupport.IsolatedH2DataJpaTest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * H2 {@code @DataJpaTest} coverage of the effective-org predicate + search query
 * on {@link TranscriptSegmentRepository}. The Postgres-specific trigger/CHECK
 * behaviour is exercised separately in the Testcontainers integration test; H2
 * here covers the JPQL query semantics (cross-tenant isolation, ordering,
 * case-insensitive substring search, draft+final coverage).
 */
@IsolatedH2DataJpaTest
class TranscriptSegmentRepositoryH2Test {

    @Autowired
    private TranscriptSegmentRepository repository;

    @Test
    void findVisibleToOrgAndId_returnsCanonicalRow() {
        UUID orgA = UUID.randomUUID();
        TranscriptSegment seg = persist(orgA, orgA, UUID.randomUUID(), 0.0, 1.0, "hello", null);

        Optional<TranscriptSegment> hit = repository.findVisibleToOrgAndId(orgA, seg.getId());
        assertThat(hit).isPresent();
        assertThat(hit.get().getId()).isEqualTo(seg.getId());
    }

    @Test
    void findVisibleToOrgAndId_crossTenant_returnsEmpty() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        TranscriptSegment segB = persist(orgB, orgB, UUID.randomUUID(), 0.0, 1.0, "b-only", null);

        Optional<TranscriptSegment> miss = repository.findVisibleToOrgAndId(orgA, segB.getId());
        assertThat(miss).isEmpty();
    }

    @Test
    void findVisibleToOrgAndId_legacyNullOrgRow_resolvesViaTenantFallback() {
        // Legacy row: org_id NULL, tenant_id set (H2 has no V1 trigger, so we
        // assert the OR-fallback branch directly).
        UUID orgA = UUID.randomUUID();
        TranscriptSegment seg = persist(orgA, null, UUID.randomUUID(), 0.0, 1.0, "legacy", null);

        Optional<TranscriptSegment> hit = repository.findVisibleToOrgAndId(orgA, seg.getId());
        assertThat(hit).as("legacy null-org row resolves via tenant_id fallback").isPresent();
    }

    @Test
    void findVisibleToOrgByMeeting_isOrderedByStartTime_andTenantScoped() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID meeting = UUID.randomUUID();
        persist(orgA, orgA, meeting, 5.0, 6.0, "third", null);
        persist(orgA, orgA, meeting, 1.0, 2.0, "first", null);
        persist(orgA, orgA, meeting, 3.0, 4.0, "second", null);
        // Same meeting id under a different tenant — must NOT leak.
        persist(orgB, orgB, meeting, 0.0, 1.0, "other-tenant", null);

        Page<TranscriptSegment> page = repository.findVisibleToOrgByMeeting(
                orgA, meeting, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent())
                .extracting(TranscriptSegment::getTextDraft)
                .containsExactly("first", "second", "third");
    }

    @Test
    void searchVisibleToOrg_isCaseInsensitive_andCoversDraftAndFinal() {
        UUID orgA = UUID.randomUUID();
        UUID meeting = UUID.randomUUID();
        persist(orgA, orgA, meeting, 0.0, 1.0, "The Quarterly BUDGET review", null);
        // match only on final text
        TranscriptSegment finalOnly = persist(orgA, orgA, meeting, 1.0, 2.0, "unrelated draft", "budget approved");
        finalOnly.setStatus(TranscriptSegmentStatus.FINALIZED);
        repository.saveAndFlush(finalOnly);
        // no match
        persist(orgA, orgA, meeting, 2.0, 3.0, "weather chat", "small talk");

        Page<TranscriptSegment> page = repository.searchVisibleToOrg(
                orgA, meeting, "budget", PageRequest.of(0, 10));

        assertThat(page.getTotalElements())
                .as("matches both the draft-text and final-text rows, case-insensitive")
                .isEqualTo(2);
    }

    @Test
    void searchVisibleToOrg_nullMeeting_searchesAcrossMeetingsWithinTenant() {
        UUID orgA = UUID.randomUUID();
        persist(orgA, orgA, UUID.randomUUID(), 0.0, 1.0, "alpha keyword", null);
        persist(orgA, orgA, UUID.randomUUID(), 0.0, 1.0, "beta keyword", null);

        Page<TranscriptSegment> page = repository.searchVisibleToOrg(
                orgA, null, "keyword", PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    private TranscriptSegment persist(UUID tenant, UUID org, UUID meeting,
                                      double start, double end, String draft, String fin) {
        TranscriptSegment seg = new TranscriptSegment();
        seg.setTenantId(tenant);
        seg.setOrgId(org);
        seg.setMeetingId(meeting);
        seg.setStartTime(start);
        seg.setEndTime(end);
        seg.setTextDraft(draft);
        seg.setTextFinal(fin);
        seg.setStatus(TranscriptSegmentStatus.DRAFT);
        return repository.saveAndFlush(seg);
    }
}
