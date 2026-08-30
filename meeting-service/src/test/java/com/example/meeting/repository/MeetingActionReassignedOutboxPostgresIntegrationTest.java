package com.example.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.meeting.events.MeetingEventOutboxFactory;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingActionStatus;
import com.example.meeting.model.MeetingEventOutbox;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Faz 24 Görevler dilim-4 — V10 outbox scope on a real Postgres engine.
 *
 * <p>Proves the migration's teeth rather than restating the factory's unit
 * behaviour: the generated {@code action_scope_id} + composite FK accept a
 * well-formed {@code meeting.action.reassigned} row, the scope CHECK rejects a
 * mis-scoped one, and deleting the action cascades its outbox rows away
 * (deleteAction is a real admin surface).
 */
@Testcontainers
@SpringBootTest(
        classes = MeetingActionReassignedOutboxPostgresIntegrationTest.Boot.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MeetingActionReassignedOutboxPostgresIntegrationTest {

    private static final String SCHEMA = "meeting_service";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
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
        registry.add("spring.jpa.open-in-view", () -> "false");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            TransactionAutoConfiguration.class
    })
    @EntityScan(basePackageClasses = Meeting.class)
    @EnableJpaRepositories(basePackageClasses = MeetingRepository.class)
    static class Boot {
    }

    @Autowired private MeetingActionRepository actionRepository;
    @Autowired private MeetingEventOutboxRepository outboxRepository;
    @Autowired private JdbcTemplate jdbc;

    private final MeetingEventOutboxFactory factory = new MeetingEventOutboxFactory();

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + SCHEMA + ".meeting_event_outbox");
        jdbc.update("DELETE FROM " + SCHEMA + ".meeting_actions");
        jdbc.update("DELETE FROM " + SCHEMA + ".meetings");
    }

    @Test
    void wellFormedReassignedRowLandsWithActionScopePopulated() {
        MeetingAction action = persistedAction("kc-veli");

        outboxRepository.save(factory.buildActionAssignment(action, "kc-ali"));

        var row = jdbc.queryForMap("SELECT event_type, event_key, aggregate_type, "
                + "aggregate_revision, action_scope_id, payload::text AS payload "
                + "FROM " + SCHEMA + ".meeting_event_outbox WHERE aggregate_id = ?", action.getId());
        assertThat(row.get("event_type")).isEqualTo("meeting.action.reassigned");
        assertThat(row.get("aggregate_type")).isEqualTo("meeting.action");
        assertThat(row.get("action_scope_id")).isEqualTo(action.getId());
        assertThat(row.get("event_key")).isEqualTo(
                "meeting.action|" + action.getId() + "|meeting.action.reassigned|"
                        + action.getVersion());
        assertThat((String) row.get("payload"))
                .contains("\"previousAssigneeSubject\": \"kc-ali\"");
    }

    @Test
    void scopeCheckRejectsAReassignedRowMisScopedToTheAnalysisAggregate() {
        MeetingAction action = persistedAction("kc-veli");
        MeetingEventOutbox row = factory.buildActionAssignment(action, null);
        row.setAggregateType("meeting.analysis.run"); // deliberately wrong scope

        assertThatThrownBy(() -> outboxRepository.saveAndFlush(row))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingTheActionCascadesItsOutboxRows() {
        MeetingAction action = persistedAction("kc-veli");
        outboxRepository.save(factory.buildActionAssignment(action, null));
        assertThat(countOutbox(action.getId())).isEqualTo(1);

        actionRepository.delete(action);
        actionRepository.flush();

        assertThat(countOutbox(action.getId())).isZero();
    }

    private int countOutbox(UUID aggregateId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + SCHEMA
                + ".meeting_event_outbox WHERE aggregate_id = ?", Integer.class, aggregateId);
        return n == null ? 0 : n;
    }

    private MeetingAction persistedAction(String assignee) {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        MeetingAction action = new MeetingAction();
        action.setMeetingId(meetingId);
        action.setTenantId(org);
        action.setOrgId(org);
        action.setDescription("Raporu hazırla");
        action.setAssigneeSubject(assignee);
        action.setStatus(MeetingActionStatus.OPEN);
        action.setCreatedBySubject("manager-sub");
        action.setLastUpdatedBySubject("manager-sub");
        return actionRepository.saveAndFlush(action);
    }

    private UUID insertMeeting(UUID org) {
        UUID meetingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO %s.meetings
                  (id, tenant_id, org_id, title, status, organizer_subject,
                   created_by_subject, last_updated_by_subject, created_at, updated_at)
                VALUES (?, ?, ?, 'outbox test', 'SCHEDULED', 'organizer', 'creator', 'updater', ?, ?)
                """.formatted(SCHEMA), meetingId, org, org,
                java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
        return meetingId;
    }
}
