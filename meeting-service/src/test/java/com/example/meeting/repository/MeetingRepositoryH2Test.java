package com.example.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.model.MeetingSession;
import com.example.meeting.model.MeetingStatus;
import com.example.meeting.testsupport.IsolatedH2DataJpaTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

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

        Page<Meeting> page = meetingRepository.findAllVisibleToOrg(org, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allSatisfy(m -> assertThat(m.getEffectiveOrgId()).isEqualTo(org));
    }

    @Test
    void listByStatus_filtersWithinOrg() {
        UUID org = UUID.randomUUID();
        meetingRepository.save(newMeeting(org, "scheduled-1", MeetingStatus.SCHEDULED));
        meetingRepository.save(newMeeting(org, "completed-1", MeetingStatus.COMPLETED));
        meetingRepository.save(newMeeting(org, "completed-2", MeetingStatus.COMPLETED));

        Page<Meeting> completed = meetingRepository.findAllVisibleToOrgByStatus(
                org, MeetingStatus.COMPLETED, PageRequest.of(0, 10));
        assertThat(completed.getTotalElements()).isEqualTo(2);
        assertThat(completed.getContent()).allSatisfy(
                m -> assertThat(m.getStatus()).isEqualTo(MeetingStatus.COMPLETED));
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

    // ───────────────────────────── Builders ─────────────────────────────

    private static Meeting newMeeting(UUID org, String title, MeetingStatus status) {
        Meeting m = new Meeting();
        m.setTenantId(org);
        m.setOrgId(org); // canonical write path (trigger-equivalent)
        m.setTitle(title);
        m.setStatus(status);
        m.setOrganizerSubject("organizer@example.com");
        m.setCreatedBySubject("creator@example.com");
        m.setLastUpdatedBySubject("creator@example.com");
        return m;
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
}
