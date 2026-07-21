package com.example.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.meeting.model.Meeting;
import com.example.meeting.dto.v1.admin.MeetingSearchCriteria;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.model.MeetingItemSource;
import com.example.meeting.model.MeetingSession;
import com.example.meeting.model.MeetingStatus;
import com.example.meeting.testsupport.IsolatedH2DataJpaTest;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 repository tests for the effective-org read predicates — Faz 24
 * (#410). Covers the canonical-row read, status filtering, cross-org
 * isolation, and meeting-scoped sub-resource visibility. H2 does not run
 * the V1 org_id trigger, so canonical rows set {@code orgId == tenantId}
 * explicitly (the write path the trigger would otherwise produce). The
 * legacy-NULL OR branch is covered by the Postgres integration test.
 */
@IsolatedH2DataJpaTest
class MeetingRepositoryH2Test {

    @Autowired
    private MeetingRepository meetingRepository;
    @Autowired
    private MeetingSessionRepository sessionRepository;
    @Autowired
    private MeetingActionRepository actionRepository;
    @Autowired
    private MeetingDecisionRepository decisionRepository;
    @Autowired
    private MeetingAnalysisRunRepository analysisRunRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    @Test
    void canonicalMeeting_isReturnedByEffectiveOrgFilter() {
        UUID org = UUID.randomUUID();
        Meeting saved = meetingRepository.save(newMeeting(org, "weekly sync", MeetingStatus.SCHEDULED));

        Optional<Meeting> hit = meetingRepository.findVisibleToOrgAndId(org, saved.getId());
        assertThat(hit).isPresent()
                .hasValueSatisfying(m -> assertThat(m.getTitle()).isEqualTo("weekly sync"));
    }

    @Test
    void crossOrg_doesNotReturnOtherOrgsMeeting() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        Meeting a = meetingRepository.save(newMeeting(orgA, "orgA meeting", MeetingStatus.SCHEDULED));
        Meeting b = meetingRepository.save(newMeeting(orgB, "orgB meeting", MeetingStatus.SCHEDULED));

        assertThat(meetingRepository.findVisibleToOrgAndId(orgA, a.getId())).isPresent();
        assertThat(meetingRepository.findVisibleToOrgAndId(orgA, b.getId()))
                .as("orgA must not see orgB's meeting")
                .isEmpty();
    }

    @Test
    void list_isPagedAndOrgScoped() {
        UUID org = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        meetingRepository.save(newMeeting(org, "m1", MeetingStatus.SCHEDULED));
        meetingRepository.save(newMeeting(org, "m2", MeetingStatus.COMPLETED));
        meetingRepository.save(newMeeting(otherOrg, "other", MeetingStatus.SCHEDULED));

        Page<Meeting> page = search(
                org, MeetingSearchCriteria.from(null, null, null, null, null), 0, 10);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allSatisfy(m -> assertThat(m.getEffectiveOrgId()).isEqualTo(org));
    }

    @Test
    void listByStatus_filtersWithinOrg() {
        UUID org = UUID.randomUUID();
        meetingRepository.save(newMeeting(org, "scheduled-1", MeetingStatus.SCHEDULED));
        meetingRepository.save(newMeeting(org, "completed-1", MeetingStatus.COMPLETED));
        meetingRepository.save(newMeeting(org, "completed-2", MeetingStatus.COMPLETED));

        Page<Meeting> completed = search(
                org,
                MeetingSearchCriteria.from(MeetingStatus.COMPLETED, null, null, null, null),
                0,
                10);
        assertThat(completed.getTotalElements()).isEqualTo(2);
        assertThat(completed.getContent()).allSatisfy(
                m -> assertThat(m.getStatus()).isEqualTo(MeetingStatus.COMPLETED));
    }

    @Test
    void historySearchFindsBeyondFirstPageAndKeepsStableStartedAtIdOrder() {
        UUID org = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        Instant newerStartedAt = Instant.parse("2026-07-18T10:00:00Z");
        Instant targetStartedAt = Instant.parse("2026-06-15T09:00:00Z");
        List<UUID> newerIds = IntStream.rangeClosed(1, 54)
                .mapToObj(this::stableUuid)
                .toList();
        newerIds.forEach(id -> insertSearchMeeting(
                id, org, "Ordinary meeting " + id, newerStartedAt, MeetingStatus.COMPLETED));
        UUID targetId = stableUuid(0);
        insertSearchMeeting(
                targetId, org, "Quarterly Roadmap Review", targetStartedAt, MeetingStatus.COMPLETED);
        insertSearchMeeting(
                stableUuid(99), otherOrg, "Quarterly Roadmap Review", targetStartedAt,
                MeetingStatus.COMPLETED);
        entityManager.clear();

        MeetingSearchCriteria all = MeetingSearchCriteria.from(null, null, null, null, null);
        Page<Meeting> firstPage = search(org, all, 0, 50);
        assertThat(firstPage.getTotalElements()).isEqualTo(55);
        assertThat(firstPage.getContent())
                .extracting(Meeting::getId)
                .doesNotContain(targetId);

        MeetingSearchCriteria byTitle = MeetingSearchCriteria.from(
                null, "roadmap", null, null, null);
        assertThat(search(org, byTitle, 0, 25).getContent())
                .extracting(Meeting::getId)
                .containsExactly(targetId);

        MeetingSearchCriteria byId = MeetingSearchCriteria.from(
                null, null, targetId, null, null);
        assertThat(search(org, byId, 0, 25).getContent())
                .extracting(Meeting::getId)
                .containsExactly(targetId);

        MeetingSearchCriteria byDate = MeetingSearchCriteria.from(
                null,
                null,
                null,
                "2026-06-15T00:00:00Z",
                "2026-06-16T00:00:00Z");
        assertThat(search(org, byDate, 0, 25).getContent())
                .extracting(Meeting::getId)
                .containsExactly(targetId);

        List<UUID> expectedOrder = IntStream.iterate(54, value -> value - 1)
                .limit(55)
                .mapToObj(this::stableUuid)
                .toList();
        List<UUID> actualOrder = IntStream.range(0, 3)
                .mapToObj(page -> search(org, all, page, 20).getContent())
                .flatMap(List::stream)
                .map(Meeting::getId)
                .toList();
        assertThat(actualOrder)
                .containsExactlyElementsOf(expectedOrder)
                .doesNotHaveDuplicates();
        assertThat(search(org, all, 1, 20).getContent())
                .extracting(Meeting::getId)
                .containsExactlyElementsOf(expectedOrder.subList(20, 40));
    }

    @Test
    void historySearchIntersectsFiltersInsideEffectiveOrgScope() {
        UUID org = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        Meeting matching = newMeeting(org, "Customer Review", MeetingStatus.COMPLETED);
        matching.setStartedAt(Instant.parse("2026-07-10T09:00:00Z"));
        Meeting wrongStatus = newMeeting(org, "Customer Review", MeetingStatus.SCHEDULED);
        wrongStatus.setStartedAt(Instant.parse("2026-07-10T09:00:00Z"));
        Meeting foreign = newMeeting(otherOrg, "Customer Review", MeetingStatus.COMPLETED);
        foreign.setStartedAt(Instant.parse("2026-07-10T09:00:00Z"));
        meetingRepository.saveAllAndFlush(List.of(matching, wrongStatus, foreign));

        MeetingSearchCriteria criteria = MeetingSearchCriteria.from(
                MeetingStatus.COMPLETED,
                "customer",
                matching.getId(),
                "2026-07-10T00:00:00Z",
                "2026-07-11T00:00:00Z");

        assertThat(search(org, criteria, 0, 25).getContent())
                .extracting(Meeting::getId)
                .containsExactly(matching.getId());
        assertThat(search(otherOrg, MeetingSearchCriteria.from(
                null, null, matching.getId(), null, null), 0, 25).getContent())
                .as("a meeting id must not bypass the effective-org predicate")
                .isEmpty();
    }

    @Test
    void subResources_areVisibleOnlyThroughOwningMeetingAndOrg() {
        UUID org = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        Meeting meeting = meetingRepository.save(newMeeting(org, "host", MeetingStatus.IN_PROGRESS));

        MeetingSession session = sessionRepository.save(newSession(meeting.getId(), org));
        MeetingAction action = actionRepository.save(newAction(meeting.getId(), org));
        MeetingDecision decision = decisionRepository.save(newDecision(meeting.getId(), org));

        // Children list for the owning org.
        List<MeetingSession> sessions = sessionRepository.findByMeetingIdVisibleToOrg(meeting.getId(), org);
        assertThat(sessions).extracting(MeetingSession::getId).containsExactly(session.getId());
        assertThat(actionRepository.findByMeetingIdVisibleToOrg(meeting.getId(), org))
                .extracting(MeetingAction::getId).containsExactly(action.getId());
        assertThat(decisionRepository.findByMeetingIdVisibleToOrg(meeting.getId(), org))
                .extracting(MeetingDecision::getId).containsExactly(decision.getId());

        // Single-child resolve by id + meeting + org.
        assertThat(sessionRepository.findByIdAndMeetingIdVisibleToOrg(session.getId(), meeting.getId(), org))
                .isPresent();

        // A different org cannot see the children even with the right ids.
        assertThat(sessionRepository.findByMeetingIdVisibleToOrg(meeting.getId(), otherOrg)).isEmpty();
        assertThat(sessionRepository.findByIdAndMeetingIdVisibleToOrg(session.getId(), meeting.getId(), otherOrg))
                .isEmpty();
    }

    @Test
    void externalSessionLookupIsMeetingAndOrgScoped() {
        UUID org = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        Meeting meeting = meetingRepository.save(newMeeting(org, "recording", MeetingStatus.IN_PROGRESS));
        MeetingSession session = newSession(meeting.getId(), org);
        session.setExternalSessionId("SES-stable-1");
        sessionRepository.saveAndFlush(session);

        assertThat(sessionRepository.findByExternalSessionIdVisibleToOrg(
                meeting.getId(), "SES-stable-1", org)).isPresent();
        assertThat(sessionRepository.findByExternalSessionIdVisibleToOrg(
                meeting.getId(), "SES-stable-1", otherOrg)).isEmpty();
        assertThat(sessionRepository.findByExternalSessionIdVisibleToOrg(
                UUID.randomUUID(), "SES-stable-1", org)).isEmpty();
    }

    @Test
    void intelligenceRead_selectsLatestRunAndOnlyItsOrdinalOrderedChildren() {
        UUID org = UUID.randomUUID();
        Meeting meeting = meetingRepository.save(newMeeting(org, "AI sync", MeetingStatus.COMPLETED));
        MeetingAnalysisRun older = analysisRun(
                meeting.getId(), org, Instant.parse("2026-07-11T09:00:00Z"));
        MeetingAnalysisRun latest = analysisRun(
                meeting.getId(), org, Instant.parse("2026-07-11T10:00:00Z"));
        analysisRunRepository.saveAllAndFlush(List.of(older, latest));

        actionRepository.save(newAction(meeting.getId(), org));
        actionRepository.save(aiAction(meeting.getId(), org, older.getAnalysisRunId(), 0, "old"));
        actionRepository.save(aiAction(meeting.getId(), org, latest.getAnalysisRunId(), 1, "second"));
        actionRepository.save(aiAction(meeting.getId(), org, latest.getAnalysisRunId(), 0, "first"));
        decisionRepository.save(aiDecision(meeting.getId(), org, older.getAnalysisRunId(), 0, "old"));
        decisionRepository.save(aiDecision(meeting.getId(), org, latest.getAnalysisRunId(), 0, "current"));

        assertThat(analysisRunRepository.findLatestByMeetingIdVisibleToOrg(meeting.getId(), org))
                .get()
                .extracting(MeetingAnalysisRun::getAnalysisRunId)
                .isEqualTo(latest.getAnalysisRunId());
        assertThat(actionRepository.findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                latest.getAnalysisRunId(), meeting.getId(), org))
                .extracting(MeetingAction::getDescription)
                .containsExactly("first", "second");
        assertThat(decisionRepository.findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                latest.getAnalysisRunId(), meeting.getId(), org))
                .extracting(MeetingDecision::getDetail)
                .containsExactly("current");

        UUID otherOrg = UUID.randomUUID();
        assertThat(analysisRunRepository.findLatestByMeetingIdVisibleToOrg(
                meeting.getId(), otherOrg)).isEmpty();
        assertThat(actionRepository.findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                latest.getAnalysisRunId(), meeting.getId(), otherOrg)).isEmpty();
    }

    @Test
    void intelligenceRead_breaksExactTimestampTiesByRunId() {
        UUID org = UUID.randomUUID();
        Meeting meeting = meetingRepository.save(newMeeting(org, "Tie break", MeetingStatus.COMPLETED));
        Instant generatedAt = Instant.parse("2026-07-11T10:00:00Z");
        UUID lowerRunId = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID higherRunId = UUID.fromString("44444444-4444-4444-8444-444444444444");
        analysisRunRepository.saveAllAndFlush(List.of(
                analysisRun(lowerRunId, meeting.getId(), org, generatedAt),
                analysisRun(higherRunId, meeting.getId(), org, generatedAt)));

        jdbcTemplate.update(
                "update meeting_analysis_runs set created_at = ? where analysis_run_id in (?, ?)",
                Timestamp.from(Instant.parse("2026-07-11T10:00:01Z")),
                lowerRunId,
                higherRunId);
        entityManager.clear();

        assertThat(analysisRunRepository.findLatestByMeetingIdVisibleToOrg(meeting.getId(), org))
                .get()
                .extracting(MeetingAnalysisRun::getAnalysisRunId)
                .isEqualTo(higherRunId);
    }

    // ───────────────────────────── Builders ─────────────────────────────

    private static Meeting newMeeting(UUID org, String title, MeetingStatus status) {
        Meeting m = new Meeting();
        m.setTenantId(org);
        m.setOrgId(org); // canonical write path (trigger-equivalent)
        m.setTitle(title);
        m.setStatus(status);
        m.setStartedAt(Instant.parse("2026-06-16T10:00:00Z"));
        m.setOrganizerSubject("organizer@example.com");
        m.setCreatedBySubject("creator@example.com");
        m.setLastUpdatedBySubject("creator@example.com");
        return m;
    }

    private Page<Meeting> search(
            UUID org, MeetingSearchCriteria criteria, int page, int size) {
        return meetingRepository.searchVisibleToOrg(
                org,
                criteria.status() != null,
                criteria.status(),
                criteria.title() != null,
                criteria.title(),
                criteria.meetingId() != null,
                criteria.meetingId(),
                criteria.dateFrom() != null,
                criteria.dateFrom(),
                criteria.dateTo(),
                PageRequest.of(page, size));
    }

    private UUID stableUuid(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }

    private void insertSearchMeeting(
            UUID id, UUID org, String title, Instant startedAt, MeetingStatus status) {
        Timestamp timestamp = Timestamp.from(startedAt);
        jdbcTemplate.update("""
                insert into meetings
                    (id, tenant_id, org_id, title, status, scheduled_start, started_at,
                     organizer_subject, created_by_subject, last_updated_by_subject,
                     created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, ?, 'organizer', 'creator', 'creator', ?, ?, 0)
                """,
                id,
                org,
                org,
                title,
                status.name(),
                timestamp,
                timestamp,
                timestamp,
                timestamp);
    }

    private static MeetingSession newSession(UUID meetingId, UUID org) {
        MeetingSession s = new MeetingSession();
        s.setMeetingId(meetingId);
        s.setTenantId(org);
        s.setOrgId(org);
        s.setSessionLabel("session-1");
        s.setStartedAt(Instant.parse("2026-06-16T10:00:00Z"));
        s.setCreatedBySubject("creator@example.com");
        s.setLastUpdatedBySubject("creator@example.com");
        return s;
    }

    private static MeetingAction newAction(UUID meetingId, UUID org) {
        MeetingAction a = new MeetingAction();
        a.setMeetingId(meetingId);
        a.setTenantId(org);
        a.setOrgId(org);
        a.setDescription("follow up with vendor");
        a.setCreatedBySubject("creator@example.com");
        a.setLastUpdatedBySubject("creator@example.com");
        return a;
    }

    private static MeetingAnalysisRun analysisRun(UUID meetingId, UUID org, Instant generatedAt) {
        return analysisRun(UUID.randomUUID(), meetingId, org, generatedAt);
    }

    private static MeetingAnalysisRun analysisRun(
            UUID runId,
            UUID meetingId,
            UUID org,
            Instant generatedAt) {
        MeetingAnalysisRun run = new MeetingAnalysisRun();
        run.setAnalysisRunId(runId);
        run.setMeetingId(meetingId);
        run.setTenantId(org);
        run.setOrgId(org);
        run.setTranscriptSessionId("SES-1");
        run.setTranscriptSha256("a".repeat(64));
        run.setAnalyzerContractVersion("5-adr0043");
        run.setPayloadHash(UUID.randomUUID().toString().replace("-", "") + "0".repeat(32));
        run.setGeneratedAt(generatedAt);
        return run;
    }

    private static MeetingAction aiAction(
            UUID meetingId,
            UUID org,
            UUID runId,
            int ordinal,
            String description) {
        MeetingAction action = newAction(meetingId, org);
        action.setDescription(description);
        action.setSource(MeetingItemSource.AI_ANALYSIS);
        action.setAnalysisRunId(runId);
        action.setOrdinal(ordinal);
        return action;
    }

    private static MeetingDecision newDecision(UUID meetingId, UUID org) {
        MeetingDecision d = new MeetingDecision();
        d.setMeetingId(meetingId);
        d.setTenantId(org);
        d.setOrgId(org);
        d.setTitle("approve budget");
        d.setCreatedBySubject("creator@example.com");
        d.setLastUpdatedBySubject("creator@example.com");
        return d;
    }

    private static MeetingDecision aiDecision(
            UUID meetingId,
            UUID org,
            UUID runId,
            int ordinal,
            String detail) {
        MeetingDecision decision = newDecision(meetingId, org);
        decision.setDetail(detail);
        decision.setSource(MeetingItemSource.AI_ANALYSIS);
        decision.setAnalysisRunId(runId);
        decision.setOrdinal(ordinal);
        return decision;
    }
}
