package com.example.meeting.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.common.meeting.events.RecordingOutcome;
import com.example.meeting.dto.v1.admin.MeetingSessionProcessingStatusResponse.SavedState;
import com.example.meeting.repository.*;
import com.example.meeting.security.AdminTenantContext;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MeetingSessionProcessingStatusPostgresIntegrationTest {
    private static final String SCHEMA = "meeting_service";
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("meeting").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.jpa.open-in-view", () -> "false");
    }

    @Autowired private MeetingRepository meetings;
    @Autowired private MeetingSessionRepository sessions;
    @Autowired private MeetingSessionErasureRepository erasures;
    @Autowired private MeetingAnalysisRunRepository results;
    @Autowired private MeetingIntelligenceResultAccessAuditRepository audits;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void exactSessionLegacyOrgVisibilityAndAuditSurviveSessionDeletion() {
        var fixture = fixture();
        UUID expected = insertRun(fixture, fixture.session());
        UUID otherSession = UUID.randomUUID();
        insertSession(fixture.tenant(), fixture.meeting(), otherSession);
        insertRun(fixture, otherSession);
        for (String table : List.of("meetings", "meeting_sessions", "meeting_analysis_runs")) {
            jdbc.execute("ALTER TABLE " + SCHEMA + "." + table + " DISABLE TRIGGER " + table + "_org_id_compat");
            try {
                jdbc.update("UPDATE " + SCHEMA + "." + table + " SET org_id=NULL WHERE tenant_id=?", fixture.tenant());
            } finally {
                jdbc.execute("ALTER TABLE " + SCHEMA + "." + table + " ENABLE TRIGGER " + table + "_org_id_compat");
            }
        }
        var response = service(fixture, ignored -> observation(fixture), audit()).read(
                fixture.context(), fixture.meeting(), fixture.session());
        assertThat(response.savedResult().analysisRunId()).isEqualTo(expected);
        assertThat(response.savedResult().matchesCurrentSourceOccurrence()).isNull();
        var row = audits.findByTenantIdOrderByAccessedAtDesc(fixture.tenant()).getFirst();
        assertThat(row.getSessionId()).isEqualTo(fixture.session());
        assertThat(row.getAnalysisRunId()).isNull();
        assertThat(row.getOrgId()).isEqualTo(fixture.tenant());
        assertThat(row.getAccessorSubject()).isEqualTo("owner");
        new TransactionTemplate(transactions).execute(tx -> {
            assertThat(results.findLatestBySessionVisibleToOrgForStatus(
                    fixture.meeting(), UUID.randomUUID(), fixture.session().toString())).isEmpty();
            assertThat(results.findLatestBySessionVisibleToOrgForStatus(
                    UUID.randomUUID(), fixture.tenant(), fixture.session().toString())).isEmpty();
            return null;
        });
        jdbc.update("DELETE FROM " + SCHEMA + ".meeting_sessions WHERE id=?", fixture.session());
        assertThat(audits.findById(row.getId())).isPresent();
    }

    @Test
    void readLocksResultUntilAuditCommitAgainstRetentionDeletion() {
        var fixture = fixture();
        UUID run = insertRun(fixture, fixture.session());
        var audit = spy(audit());
        try (var executor = Executors.newSingleThreadExecutor()) {
            doAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                var deletion = executor.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                    jdbc.execute("SET LOCAL lock_timeout = '250ms'");
                    return results.deleteByIdIn(List.of(run));
                }));
                lockTimeout(deletion);
                return call.callRealMethod();
            }).when(audit).recordProcessingStatus(fixture.context(), fixture.meeting(), fixture.session());
            assertThat(service(fixture, ignored -> observation(fixture), audit).read(
                    fixture.context(), fixture.meeting(), fixture.session()).savedResult().state())
                    .isEqualTo(SavedState.AVAILABLE);
        }
        new TransactionTemplate(transactions).execute(tx -> results.deleteByIdIn(List.of(run)));
        assertThat(service(fixture, ignored -> observation(fixture), audit()).read(
                fixture.context(), fixture.meeting(), fixture.session()).savedResult().state())
                .isEqualTo(SavedState.NOT_FOUND);
    }

    @Test
    void deletionThatLocksFirstPreventsReadingUntilItCommits() {
        var fixture = fixture();
        UUID run = insertRun(fixture, fixture.session());
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).execute(tx -> {
                assertThat(results.deleteByIdIn(List.of(run))).isEqualTo(1);
                var read = executor.submit(() -> new TransactionTemplate(transactions).execute(other -> {
                    jdbc.execute("SET LOCAL lock_timeout = '250ms'");
                    return results.findLatestBySessionVisibleToOrgForStatus(
                            fixture.meeting(), fixture.tenant(), fixture.session().toString());
                }));
                lockTimeout(read);
                return null;
            });
        }
        assertThat(service(fixture, ignored -> observation(fixture), audit()).read(
                fixture.context(), fixture.meeting(), fixture.session()).savedResult().state())
                .isEqualTo(SavedState.NOT_FOUND);
    }

    @Test
    void parentLockOrdersStatusBeforeErasureRequest() {
        var fixture = fixture();
        var audit = spy(audit());
        try (var executor = Executors.newSingleThreadExecutor()) {
            doAnswer(call -> {
                var erasure = executor.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                    jdbc.execute("SET LOCAL lock_timeout = '250ms'");
                    return meetings.findVisibleToOrgAndIdForUpdate(fixture.tenant(), fixture.meeting());
                }));
                lockTimeout(erasure);
                return call.callRealMethod();
            }).when(audit).recordProcessingStatus(fixture.context(), fixture.meeting(), fixture.session());
            service(fixture, ignored -> observation(fixture), audit).read(
                    fixture.context(), fixture.meeting(), fixture.session());
        }
    }

    @Test
    void committedErasureDuringUpstreamCallIsSeenBeforeDeletedSessionAndNotAudited() {
        var fixture = fixture();
        var service = service(fixture, ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            new TransactionTemplate(transactions).execute(tx -> {
                meetings.findVisibleToOrgAndIdForUpdate(fixture.tenant(), fixture.meeting()).orElseThrow();
                jdbc.update("""
                        INSERT INTO meeting_service.meeting_session_erasure
                        (session_id,tenant_id,org_id,meeting_id,status,local_erased,remote_erased,
                         next_attempt_at,requested_at,completed_at,updated_at)
                        VALUES (?,?,?,?,'COMPLETE',true,true,now(),now(),now(),now())
                        """, fixture.session(), fixture.tenant(), fixture.tenant(), fixture.meeting());
                jdbc.update("DELETE FROM " + SCHEMA + ".meeting_sessions WHERE id=?", fixture.session());
                return null;
            });
            return observation(fixture);
        }, audit());
        assertThatThrownBy(() -> service.read(fixture.context(), fixture.meeting(), fixture.session()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(410));
        assertThat(audits.findByTenantIdOrderByAccessedAtDesc(fixture.tenant())).isEmpty();
    }

    @Test
    void auditFailureRollsBackItsInsertAndDoesNotReturnAStatus() {
        var fixture = fixture();
        var audit = spy(audit());
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("injected audit failure");
        }).when(audit).recordProcessingStatus(fixture.context(), fixture.meeting(), fixture.session());
        assertThatThrownBy(() -> service(fixture, ignored -> observation(fixture), audit).read(
                fixture.context(), fixture.meeting(), fixture.session())).isInstanceOf(IllegalStateException.class);
        assertThat(audits.findByTenantIdOrderByAccessedAtDesc(fixture.tenant())).isEmpty();
    }

    private MeetingSessionProcessingStatusService service(Fixture fixture,
            java.util.function.Function<UUID, TranscriptSourceStatusClient.Observation> remote,
            MeetingIntelligenceResultAccessAuditService audit) {
        var owner = mock(MeetingCanonicalTranscriptService.class);
        TranscriptSourceStatusClient client = (tenant, meeting, session) -> {
            assertThat(tenant).isEqualTo(fixture.tenant());
            assertThat(meeting).isEqualTo(fixture.meeting());
            assertThat(session).isEqualTo(fixture.session());
            return remote.apply(session);
        };
        return new MeetingSessionProcessingStatusService(owner, meetings, sessions, erasures, results, client, audit, transactions);
    }

    private MeetingIntelligenceResultAccessAuditService audit() {
        return new MeetingIntelligenceResultAccessAuditService(audits);
    }

    private static TranscriptSourceStatusClient.Observation observation(Fixture fixture) {
        return new TranscriptSourceStatusClient.Observation(fixture.tenant(), fixture.meeting(), fixture.session(),
                TranscriptSourceStatusClient.State.QUIESCING, 1L, 2L, Instant.now(), null, RecordingOutcome.FINISHED, null, null);
    }

    private static void lockTimeout(Future<?> operation) {
        assertThatThrownBy(() -> operation.get(5, TimeUnit.SECONDS)).rootCause()
                .isInstanceOf(SQLException.class).extracting("SQLState").isEqualTo("55P03");
    }

    private Fixture fixture() {
        var fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("""
                INSERT INTO meeting_service.meetings
                (id,tenant_id,title,status,organizer_subject,created_by_subject,last_updated_by_subject,created_at,updated_at)
                VALUES (?,?,'fixture','SCHEDULED','owner','owner','owner',now(),now())
                """, fixture.meeting(), fixture.tenant());
        insertSession(fixture.tenant(), fixture.meeting(), fixture.session());
        return fixture;
    }

    private void insertSession(UUID tenant, UUID meeting, UUID session) {
        jdbc.update("""
                INSERT INTO meeting_service.meeting_sessions
                (id,tenant_id,meeting_id,created_by_subject,last_updated_by_subject,created_at,updated_at)
                VALUES (?,?,?,'owner','owner',now(),now())
                """, session, tenant, meeting);
    }

    private UUID insertRun(Fixture fixture, UUID session) {
        UUID run = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO meeting_service.meeting_analysis_runs
                (analysis_run_id,meeting_id,tenant_id,transcript_session_id,transcript_sha256,
                 analyzer_contract_version,payload_hash,generated_at,created_at,updated_at)
                VALUES (?,?,?,?,?,'5-adr0043',?,now(),now(),now())
                """, run, fixture.meeting(), fixture.tenant(), session.toString(), "a".repeat(64), "b".repeat(64));
        return run;
    }

    private record Fixture(UUID tenant, UUID meeting, UUID session) {
        AdminTenantContext context() { return new AdminTenantContext(tenant, "owner", "module-user"); }
    }
}
