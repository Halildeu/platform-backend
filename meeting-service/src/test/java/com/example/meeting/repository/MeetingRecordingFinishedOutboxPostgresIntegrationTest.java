package com.example.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.dto.v1.admin.RecordingLifecycleSyncRequest;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.service.MeetingService;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MeetingRecordingFinishedOutboxPostgresIntegrationTest {

    private static final String SCHEMA = "meeting_service";
    private static final Instant STARTED_AT = Instant.parse("2026-07-17T08:43:20Z");
    private static final Instant ENDED_AT = Instant.parse("2026-07-17T08:44:20Z");

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("meeting")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired private MeetingRepository meetingRepository;
    @Autowired private MeetingSessionRepository sessionRepository;
    @Autowired private MeetingEventOutboxRepository outboxRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void firstFinishPersistsOneScopedEventAndReplayIsNoOp() {
        Fixture fixture = insertFixture("success");
        MeetingService service = service();

        inTransaction(() -> service.syncRecordingLifecycle(
                fixture.tenant(), fixture.meeting(),
                new RecordingLifecycleSyncRequest(fixture.externalSessionId(), STARTED_AT, ENDED_AT)));
        inTransaction(() -> service.syncRecordingLifecycle(
                fixture.tenant(), fixture.meeting(),
                new RecordingLifecycleSyncRequest(
                        fixture.externalSessionId(), STARTED_AT, ENDED_AT)));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_id, aggregate_revision,
                       event_key, payload::text AS payload, payload_raw
                FROM meeting_service.meeting_event_outbox
                WHERE aggregate_id = ?
                """, fixture.session());
        assertThat(row.get("event_type")).isEqualTo("meeting.recording.finished");
        assertThat(row.get("aggregate_type")).isEqualTo("meeting.recording");
        assertThat(row.get("aggregate_id")).isEqualTo(fixture.session());
        assertThat(((Number) row.get("aggregate_revision")).longValue()).isEqualTo(1);
        assertThat(row.get("event_key")).isEqualTo(
                "meeting.recording|" + fixture.session() + "|meeting.recording.finished|1");
        assertThat(row.get("payload").toString())
                .contains(fixture.session().toString(), fixture.externalSessionId(), ENDED_AT.toString())
                .doesNotContain("recordingUri", "audio", "transcriptText");
        assertThat(row.get("payload_raw")).isEqualTo(
                "{\"schema\":\"meeting.event.v1\","
                        + "\"eventType\":\"meeting.recording.finished\","
                        + "\"analysisRunId\":null,"
                        + "\"meetingId\":\"" + fixture.meeting() + "\","
                        + "\"tenantId\":\"" + fixture.tenant().tenantId() + "\","
                        + "\"orgId\":\"" + fixture.tenant().tenantId() + "\","
                        + "\"generatedAt\":\"" + ENDED_AT + "\","
                        + "\"recordingSessionId\":\"" + fixture.session() + "\","
                        + "\"externalSessionId\":\"" + fixture.externalSessionId() + "\","
                        + "\"finishedAt\":\"" + ENDED_AT + "\"}");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM meeting_service.meeting_event_outbox WHERE aggregate_id = ?",
                Long.class, fixture.session())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT ended_at FROM meeting_service.meeting_sessions WHERE id = ?",
                Timestamp.class, fixture.session()).toInstant()).isEqualTo(ENDED_AT);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void outboxInsertFailureRollsBackTheFinishedTransition() {
        Fixture fixture = insertFixture("rollback");
        MeetingService service = service();
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION meeting_service.reject_recording_finished_test()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_type = 'meeting.recording.finished' THEN
                        RAISE EXCEPTION 'forced outbox failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_recording_finished_test
                BEFORE INSERT ON meeting_service.meeting_event_outbox
                FOR EACH ROW EXECUTE FUNCTION meeting_service.reject_recording_finished_test()
                """);
        try {
            assertThatThrownBy(() -> inTransaction(() -> service.syncRecordingLifecycle(
                    fixture.tenant(), fixture.meeting(),
                    new RecordingLifecycleSyncRequest(
                            fixture.externalSessionId(), STARTED_AT, ENDED_AT))))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_recording_finished_test "
                    + "ON meeting_service.meeting_event_outbox");
            jdbc.execute("DROP FUNCTION IF EXISTS meeting_service.reject_recording_finished_test()");
        }

        assertThat(jdbc.queryForObject(
                "SELECT ended_at FROM meeting_service.meeting_sessions WHERE id = ?",
                Timestamp.class, fixture.session())).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM meeting_service.meeting_event_outbox WHERE aggregate_id = ?",
                Long.class, fixture.session())).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void v7RejectsEventTypeAggregateScopeOrRevisionDrift() {
        Fixture fixture = insertFixture("scope");
        MeetingService service = service();
        inTransaction(() -> service.syncRecordingLifecycle(
                fixture.tenant(), fixture.meeting(),
                new RecordingLifecycleSyncRequest(fixture.externalSessionId(), STARTED_AT, ENDED_AT)));

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE meeting_service.meeting_event_outbox
                SET aggregate_revision = 2
                WHERE aggregate_id = ?
                """, fixture.session()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE meeting_service.meeting_event_outbox
                SET aggregate_type = 'meeting.transcript'
                WHERE aggregate_id = ?
                """, fixture.session()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE meeting_service.meeting_event_outbox
                SET payload_raw = '{}'
                WHERE aggregate_id = ?
                """, fixture.session()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void incompleteSurvivesReloadAndLateWritersCannotPromoteIt() {
        Fixture fixture = insertFixture("incomplete");
        var command = new RecordingLifecycleSyncRequest(fixture.externalSessionId(), STARTED_AT, ENDED_AT);
        inTransaction(() -> service().abandonRecording(fixture.tenant(), fixture.meeting(), command));
        inTransaction(() -> assertThat(service().abandonRecording(fixture.tenant(), fixture.meeting(), command).recordingIncomplete()).isTrue());
        assertThatThrownBy(() -> inTransaction(() -> service().syncRecordingLifecycle(fixture.tenant(), fixture.meeting(), command)))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting_service.meeting_event_outbox WHERE aggregate_id = ?", Long.class, fixture.session())).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE meeting_service.meeting_sessions SET recording_incomplete = false WHERE id = ?", fixture.session()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE meeting_service.meeting_sessions SET transcript_status = 'COMPLETED' WHERE id = ?", fixture.session()))
                .isInstanceOf(DataIntegrityViolationException.class);
        inTransaction(() -> assertThat(sessionRepository.countIncomplete(fixture.meeting(), fixture.tenant().tenantId())).isEqualTo(1));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void finishAndAbandonRaceHasExactlyOneWinnerAndConsistentOutbox() throws Exception {
        Fixture fixture = insertFixture("race");
        var command = new RecordingLifecycleSyncRequest(fixture.externalSessionId(), STARTED_AT, ENDED_AT);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var finish = executor.submit(() -> { start.await(); try {
                inTransaction(() -> service().syncRecordingLifecycle(fixture.tenant(), fixture.meeting(), command)); return true;
            } catch (org.springframework.web.server.ResponseStatusException conflict) { return false; } });
            var abandon = executor.submit(() -> { start.await(); try {
                inTransaction(() -> service().abandonRecording(fixture.tenant(), fixture.meeting(), command)); return true;
            } catch (org.springframework.web.server.ResponseStatusException conflict) { return false; } });
            start.countDown();
            assertThat(finish.get(15, java.util.concurrent.TimeUnit.SECONDS)).isNotEqualTo(abandon.get(15, java.util.concurrent.TimeUnit.SECONDS));
        }
        boolean incomplete = jdbc.queryForObject("SELECT recording_incomplete FROM meeting_service.meeting_sessions WHERE id = ?", Boolean.class, fixture.session());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting_service.meeting_event_outbox WHERE aggregate_id = ?", Long.class, fixture.session()))
                .isEqualTo(incomplete ? 0 : 1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void abandonedSessionRollsBackWhenParentPersistenceFailsAndCanRetry() {
        Fixture fixture = insertFixture("abandon-rollback");
        var command = new RecordingLifecycleSyncRequest(fixture.externalSessionId(), STARTED_AT, ENDED_AT);
        jdbc.execute("CREATE FUNCTION meeting_service.reject_abandon_parent() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture write failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_abandon_parent BEFORE UPDATE ON meeting_service.meetings FOR EACH ROW EXECUTE FUNCTION meeting_service.reject_abandon_parent()");
        try {
            assertThatThrownBy(() -> inTransaction(() -> service().abandonRecording(fixture.tenant(), fixture.meeting(), command))).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER reject_abandon_parent ON meeting_service.meetings");
            jdbc.execute("DROP FUNCTION meeting_service.reject_abandon_parent()");
        }
        assertThat(jdbc.queryForObject("SELECT recording_incomplete FROM meeting_service.meeting_sessions WHERE id = ?", Boolean.class, fixture.session())).isFalse();
        inTransaction(() -> assertThat(service().abandonRecording(fixture.tenant(), fixture.meeting(), command).recordingIncomplete()).isTrue());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void incompleteCountIncludesAnotherSessionButExcludesAnotherOrganization() {
        Fixture valid = insertFixture("mixed-valid");
        Fixture incomplete = insertFixture("mixed-incomplete");
        jdbc.update("UPDATE meeting_service.meeting_sessions SET meeting_id = ?, tenant_id = ?, org_id = ? WHERE id = ?",
                valid.meeting(), valid.tenant().tenantId(), valid.tenant().tenantId(), incomplete.session());
        inTransaction(() -> service().syncRecordingLifecycle(valid.tenant(), valid.meeting(),
                new RecordingLifecycleSyncRequest(valid.externalSessionId(), STARTED_AT, ENDED_AT)));
        inTransaction(() -> service().abandonRecording(valid.tenant(), valid.meeting(),
                new RecordingLifecycleSyncRequest(incomplete.externalSessionId(), STARTED_AT, ENDED_AT)));
        assertThat(sessionRepository.countIncomplete(valid.meeting(), valid.tenant().tenantId())).isEqualTo(1);
        assertThat(sessionRepository.countIncomplete(valid.meeting(), incomplete.tenant().tenantId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting_service.meeting_event_outbox WHERE aggregate_id = ?", Long.class, incomplete.session())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meeting_service.meeting_event_outbox WHERE aggregate_id = ?", Long.class, valid.session())).isEqualTo(1);
    }

    private MeetingService service() {
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenFgaAuthzService> authzProvider = mock(ObjectProvider.class);
        OpenFgaAuthzService authz = mock(OpenFgaAuthzService.class);
        when(authzProvider.getIfAvailable()).thenReturn(authz);
        when(authz.isEnabled()).thenReturn(true);
        when(authz.checkPrincipal(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        return new MeetingService(
                meetingRepository, sessionRepository,
                mock(MeetingActionRepository.class),
                mock(com.example.meeting.repository.MeetingAgendaItemRepository.class),
                mock(MeetingDecisionRepository.class),
                outboxRepository, mock(MeetingAnalysisRunRepository.class),
                mock(com.example.meeting.service.MeetingSessionErasureService.class),
                authzProvider, false, false, userId -> java.util.Optional.empty());
    }

    private Fixture insertFixture(String suffix) {
        UUID tenantId = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String externalSessionId = "SES-" + suffix;
        Instant now = Instant.parse("2026-07-17T08:40:00Z");
        jdbc.update("""
                INSERT INTO meeting_service.meetings
                    (id, tenant_id, org_id, title, status, organizer_subject,
                     created_by_subject, last_updated_by_subject, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'IN_PROGRESS', 'stable-user',
                        'stable-user', 'stable-user', ?, ?, 0)
                """, meetingId, tenantId, tenantId, "meeting-" + suffix,
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO meeting_service.meeting_sessions
                    (id, meeting_id, tenant_id, org_id, session_label, external_session_id,
                     started_at, ended_at, transcript_status, created_by_subject,
                     last_updated_by_subject, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 'PENDING',
                        'stable-user', 'stable-user', ?, ?, 0)
                """, sessionId, meetingId, tenantId, tenantId, externalSessionId,
                externalSessionId, Timestamp.from(STARTED_AT), Timestamp.from(now), Timestamp.from(now));
        return new Fixture(
                new AdminTenantContext(tenantId, "stable-user", "stable-user"),
                meetingId, sessionId, externalSessionId);
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> action.run());
    }

    private record Fixture(
            AdminTenantContext tenant,
            UUID meeting,
            UUID session,
            String externalSessionId) {
    }
}
