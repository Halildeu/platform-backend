package com.example.meeting.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Pure-POJO unit tests for the meeting-service entities — Faz 24 (#410).
 * Exercises the org_id compat fallback ({@link Meeting#getEffectiveOrgId()}
 * and the sub-resource equivalents) and the equals/hashCode-by-id
 * contract. No Spring context.
 */
class MeetingEntityTest {

    @Test
    void meeting_effectiveOrgId_prefersOrgIdWhenSet() {
        UUID org = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        Meeting m = new Meeting();
        m.setTenantId(tenant);
        m.setOrgId(org);
        assertThat(m.getEffectiveOrgId()).isEqualTo(org);
    }

    @Test
    void meeting_effectiveOrgId_fallsBackToTenantWhenOrgNull() {
        UUID tenant = UUID.randomUUID();
        Meeting m = new Meeting();
        m.setTenantId(tenant);
        assertThat(m.getOrgId()).isNull();
        assertThat(m.getEffectiveOrgId()).isEqualTo(tenant);
    }

    @Test
    void meeting_defaultStatusIsScheduled() {
        assertThat(new Meeting().getStatus()).isEqualTo(MeetingStatus.SCHEDULED);
    }

    @Test
    void meeting_prePersistUsesScheduledStartAsCanonicalHistoryStart() {
        Meeting meeting = new Meeting();
        Instant scheduledStart = Instant.parse("2026-07-18T09:00:00Z");
        meeting.setScheduledStart(scheduledStart);

        meeting.prePersist();

        assertThat(meeting.getStartedAt()).isEqualTo(scheduledStart);
    }

    @Test
    void session_effectiveOrgId_fallsBackToTenant_andDefaultsTranscriptPending() {
        UUID tenant = UUID.randomUUID();
        MeetingSession s = new MeetingSession();
        s.setTenantId(tenant);
        assertThat(s.getEffectiveOrgId()).isEqualTo(tenant);
        assertThat(s.getTranscriptStatus()).isEqualTo(TranscriptStatus.PENDING);
    }

    @Test
    void action_effectiveOrgId_prefersOrg_andDefaultsStatusOpen() {
        UUID org = UUID.randomUUID();
        MeetingAction a = new MeetingAction();
        a.setTenantId(UUID.randomUUID());
        a.setOrgId(org);
        assertThat(a.getEffectiveOrgId()).isEqualTo(org);
        assertThat(a.getStatus()).isEqualTo(MeetingActionStatus.OPEN);
    }

    @Test
    void decision_effectiveOrgId_fallsBackToTenant() {
        UUID tenant = UUID.randomUUID();
        MeetingDecision d = new MeetingDecision();
        d.setTenantId(tenant);
        assertThat(d.getEffectiveOrgId()).isEqualTo(tenant);
    }

    @Test
    void equals_isByIdReference_distinctNewEntitiesAreNotEqual() {
        // Two transient (id == null) entities are NOT equal (id-based equals
        // returns false until persisted), and equal only to themselves.
        Meeting a = new Meeting();
        Meeting b = new Meeting();
        assertThat(a).isEqualTo(a);
        assertThat(a).isNotEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(0);
    }
}
