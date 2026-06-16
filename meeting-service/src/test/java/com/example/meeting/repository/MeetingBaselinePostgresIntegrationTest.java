package com.example.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.meeting.model.Meeting;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres Testcontainers integration test for the V1 baseline — Faz 24
 * (#410). Runs the real Flyway V1 migration against a genuine Postgres
 * engine so it can exercise behaviour H2 cannot: the BEFORE INSERT/UPDATE
 * org_id compat trigger and the FK {@code ON DELETE CASCADE}.
 *
 * <p>Three assertions:
 * <ol>
 *   <li><b>Trigger back-fill</b> — a tenant_id-only INSERT (org_id omitted)
 *       lands with {@code org_id = tenant_id} (legacy-writer path).</li>
 *   <li><b>Trigger leaves explicit org_id</b> — an INSERT that supplies a
 *       (matching) org_id keeps it (canonical-writer path).</li>
 *   <li><b>Cascade delete</b> — deleting a meeting removes its
 *       session/action/decision children via the FK cascade.</li>
 * </ol>
 * Plus a legacy-NULL effective-org read check (trigger disabled) proving
 * the repository OR-fallback branch returns a row whose {@code org_id} is
 * NULL but {@code tenant_id} matches.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MeetingBaselinePostgresIntegrationTest {

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
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired
    private MeetingRepository meetingRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void trigger_backfillsOrgIdFromTenantId_whenWriterOmitsOrgId() {
        UUID tenant = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        insertMeetingTenantOnly(meetingId, tenant, "legacy-writer");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tenant_id, org_id FROM " + SCHEMA + ".meetings WHERE id = ?", meetingId);
        assertThat(row.get("org_id"))
                .as("BEFORE INSERT trigger must back-fill org_id from tenant_id")
                .isEqualTo(tenant);
        assertThat(row.get("tenant_id")).isEqualTo(tenant);
    }

    @Test
    void trigger_leavesExplicitOrgId_whenWriterSuppliesIt() {
        UUID org = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        insertMeetingCanonical(meetingId, org, "canonical-writer");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tenant_id, org_id FROM " + SCHEMA + ".meetings WHERE id = ?", meetingId);
        assertThat(row.get("org_id")).isEqualTo(org);
        assertThat(row.get("tenant_id")).isEqualTo(org);
    }

    @Test
    void deletingMeeting_cascadesToChildren() {
        UUID org = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        insertMeetingCanonical(meetingId, org, "with-children");
        insertSession(UUID.randomUUID(), meetingId, org);
        insertAction(UUID.randomUUID(), meetingId, org);
        insertDecision(UUID.randomUUID(), meetingId, org);

        assertThat(childCount("meeting_sessions", meetingId)).isEqualTo(1);
        assertThat(childCount("meeting_actions", meetingId)).isEqualTo(1);
        assertThat(childCount("meeting_decisions", meetingId)).isEqualTo(1);

        jdbc.update("DELETE FROM " + SCHEMA + ".meetings WHERE id = ?", meetingId);

        assertThat(childCount("meeting_sessions", meetingId))
                .as("FK ON DELETE CASCADE must remove sessions").isZero();
        assertThat(childCount("meeting_actions", meetingId))
                .as("FK ON DELETE CASCADE must remove actions").isZero();
        assertThat(childCount("meeting_decisions", meetingId))
                .as("FK ON DELETE CASCADE must remove decisions").isZero();
    }

    @Test
    void legacyNullOrgRow_isStillVisibleViaEffectiveOrgOrFallback() {
        // Disable the compat trigger so org_id stays the explicit NULL we
        // pass — proving the repository's parenthesized-OR fallback
        // (org_id IS NULL AND tenant_id = :orgId) returns the row. The
        // failed/successful insert runs inside the @DataJpaTest tx that is
        // rolled back, which re-enables the trigger.
        UUID tenant = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        jdbc.execute("ALTER TABLE " + SCHEMA + ".meetings DISABLE TRIGGER USER");
        insertMeetingNullOrg(meetingId, tenant, "legacy-null");

        Optional<Meeting> hit = meetingRepository.findVisibleToOrgAndId(tenant, meetingId);
        assertThat(hit)
                .as("legacy NULL-org row must resolve via the OR fallback on tenant_id")
                .isPresent()
                .hasValueSatisfying(m -> {
                    assertThat(m.getOrgId()).isNull();
                    assertThat(m.getEffectiveOrgId()).isEqualTo(tenant);
                });
    }

    @Test
    void orgIdMatchCheck_rejectsTenantOrgDrift() {
        // org_id = B while tenant_id = A → meetings_org_id_match CHECK must
        // reject (the trigger only back-fills NULL, it can't correct a drift).
        UUID tenant = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".meetings "
                        + "(id, tenant_id, org_id, title, status, organizer_subject, created_by_subject, "
                        + " last_updated_by_subject, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'drift', 'SCHEDULED', 'o@e.com', 'c@e.com', 'c@e.com', ?, ?, 0)",
                meetingId, tenant, otherOrg, NOW, NOW))
                .as("CHECK (org_id IS NULL OR org_id = tenant_id) must reject a tenant/org drift")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void compositeTenantFk_rejectsChildBoundToForeignTenantMeeting() {
        // A meeting in tenant A, then a session whose tenant_id is B but which
        // points at A's meeting_id → the composite FK
        // (meeting_id, tenant_id) -> meetings(id, tenant_id) must reject it
        // (cross-tenant child drift guard).
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        insertMeetingCanonical(meetingId, tenantA, "owning-tenant-A");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".meeting_sessions "
                        + "(id, meeting_id, tenant_id, org_id, transcript_status, created_by_subject, "
                        + " last_updated_by_subject, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', 'c@e.com', 'c@e.com', ?, ?, 0)",
                UUID.randomUUID(), meetingId, tenantB, tenantB, NOW, NOW))
                .as("composite (meeting_id, tenant_id) FK must reject a child in a different tenant")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ───────────────────────────── Seed helpers ─────────────────────────────

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-06-16T10:00:00Z"));

    private void insertMeetingTenantOnly(UUID id, UUID tenant, String title) {
        jdbc.update("INSERT INTO " + SCHEMA + ".meetings "
                        + "(id, tenant_id, title, status, organizer_subject, created_by_subject, "
                        + " last_updated_by_subject, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'SCHEDULED', 'o@e.com', 'c@e.com', 'c@e.com', ?, ?, 0)",
                id, tenant, title, NOW, NOW);
    }

    private void insertMeetingCanonical(UUID id, UUID org, String title) {
        jdbc.update("INSERT INTO " + SCHEMA + ".meetings "
                        + "(id, tenant_id, org_id, title, status, organizer_subject, created_by_subject, "
                        + " last_updated_by_subject, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'SCHEDULED', 'o@e.com', 'c@e.com', 'c@e.com', ?, ?, 0)",
                id, org, org, title, NOW, NOW);
    }

    private void insertMeetingNullOrg(UUID id, UUID tenant, String title) {
        jdbc.update("INSERT INTO " + SCHEMA + ".meetings "
                        + "(id, tenant_id, org_id, title, status, organizer_subject, created_by_subject, "
                        + " last_updated_by_subject, created_at, updated_at, version) "
                        + "VALUES (?, ?, NULL, ?, 'SCHEDULED', 'o@e.com', 'c@e.com', 'c@e.com', ?, ?, 0)",
                id, tenant, title, NOW, NOW);
    }

    private void insertSession(UUID id, UUID meetingId, UUID org) {
        jdbc.update("INSERT INTO " + SCHEMA + ".meeting_sessions "
                        + "(id, meeting_id, tenant_id, org_id, transcript_status, created_by_subject, "
                        + " last_updated_by_subject, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', 'c@e.com', 'c@e.com', ?, ?, 0)",
                id, meetingId, org, org, NOW, NOW);
    }

    private void insertAction(UUID id, UUID meetingId, UUID org) {
        jdbc.update("INSERT INTO " + SCHEMA + ".meeting_actions "
                        + "(id, meeting_id, tenant_id, org_id, description, status, created_by_subject, "
                        + " last_updated_by_subject, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'do the thing', 'OPEN', 'c@e.com', 'c@e.com', ?, ?, 0)",
                id, meetingId, org, org, NOW, NOW);
    }

    private void insertDecision(UUID id, UUID meetingId, UUID org) {
        jdbc.update("INSERT INTO " + SCHEMA + ".meeting_decisions "
                        + "(id, meeting_id, tenant_id, org_id, title, created_by_subject, "
                        + " last_updated_by_subject, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'decided', 'c@e.com', 'c@e.com', ?, ?, 0)",
                id, meetingId, org, org, NOW, NOW);
    }

    private Integer childCount(String table, UUID meetingId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA + "." + table + " WHERE meeting_id = ?",
                Integer.class, meetingId);
    }
}
