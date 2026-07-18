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
    void mutationSelectors_returnTenantScopedCanonicalIdentityAndLockedSegment() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID meeting = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        TranscriptSegment seg = persist(orgA, orgA, meeting, 0.0, 1.0, "draft", null);
        seg.setSessionId(session);
        repository.saveAndFlush(seg);

        Optional<TranscriptSegmentMutationScope> scope =
                repository.findVisibleMutationScope(orgA, seg.getId());
        Optional<TranscriptSegment> locked =
                repository.findVisibleToOrgAndIdForUpdate(orgA, seg.getId());

        assertThat(scope).contains(new TranscriptSegmentMutationScope(meeting, session));
        assertThat(locked).contains(seg);
        assertThat(repository.findVisibleMutationScope(orgB, seg.getId())).isEmpty();
        assertThat(repository.findVisibleToOrgAndIdForUpdate(orgB, seg.getId())).isEmpty();
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
    void findVisibleToOrgByMeeting_ordersEqualStartTimesBySourceWindow() {
        UUID org = UUID.randomUUID();
        UUID meeting = UUID.randomUUID();
        persistDirectWindow(org, meeting, 2L, 4L, 5L, "window-2");
        persistDirectWindow(org, meeting, 0L, 0L, 1L, "window-0");
        persistDirectWindow(org, meeting, 1L, 2L, 3L, "window-1");

        Page<TranscriptSegment> page = repository.findVisibleToOrgByMeeting(
                org, meeting, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(TranscriptSegment::getTextDraft)
                .containsExactly("window-0", "window-1", "window-2");
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

    @Test
    void findDirectSttSourceWindow_resolvesByMeetingScopedWindowIdentity() {
        UUID orgA = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        TranscriptSegment seg = persist(orgA, orgA, meetingId, 0.0, 1.0, "direct draft", null);
        seg.setSourceSystem("DIRECT_STT");
        seg.setSourceSessionId("SES-abc");
        seg.setSourceChunkSeq(5L);
        seg.setSourceWindowSeq(2L);
        seg.setSourceFirstChunkSeq(3L);
        seg.setSourceLastChunkSeq(5L);
        repository.saveAndFlush(seg);

        Optional<TranscriptSegment> hit =
                repository.findDirectSttSourceWindow(orgA, meetingId, "SES-abc", 2L);
        Optional<TranscriptSegment> miss =
                repository.findDirectSttSourceWindow(orgA, meetingId, "SES-abc", 5L);
        Optional<TranscriptSegment> otherTenant =
                repository.findDirectSttSourceWindow(
                        UUID.randomUUID(), meetingId, "SES-abc", 2L);

        assertThat(hit).isPresent();
        assertThat(hit.get().getId()).isEqualTo(seg.getId());
        assertThat(miss).isEmpty();
        assertThat(otherTenant).isEmpty();
    }

    private TranscriptSegment persistDirectWindow(
            UUID tenant,
            UUID meeting,
            long windowSeq,
            long firstChunkSeq,
            long lastChunkSeq,
            String draft) {
        TranscriptSegment segment = persist(
                tenant, tenant, meeting, 1.0d, 2.0d, draft, null);
        segment.setSourceSystem("DIRECT_STT");
        segment.setSourceSessionId("SES-order");
        segment.setSourceChunkSeq(lastChunkSeq);
        segment.setSourceWindowSeq(windowSeq);
        segment.setSourceFirstChunkSeq(firstChunkSeq);
        segment.setSourceLastChunkSeq(lastChunkSeq);
        return repository.saveAndFlush(segment);
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
