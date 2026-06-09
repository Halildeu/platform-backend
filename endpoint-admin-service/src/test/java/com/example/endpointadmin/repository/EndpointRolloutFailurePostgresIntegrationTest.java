package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.ClassificationConfidence;
import com.example.endpointadmin.model.EndpointRolloutFailure;
import com.example.endpointadmin.model.EndpointRolloutFailureEvent;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL invariants for the rollout failed-device queue (Faz 22.5 #527
 * slice-1a, contract §2/§4; Codex 019eaaf0): the partial-unique ACTIVE index,
 * the fresh-observation-after-resolution path, the append-only event trigger,
 * and org-scoped reads. Flyway runs V60 against a real PG 16 container with
 * {@code ddl-auto=validate}, so the entity mapping is also validated.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointRolloutFailurePostgresIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
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
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    EndpointRolloutFailureRepository failureRepository;

    @Autowired
    EndpointRolloutFailureEventRepository eventRepository;

    @Autowired
    JdbcTemplate jdbc;

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();
    private final String rollout = "rollout-2026-q3-agent-0.2.x";
    private final String wave = "wave-02-pilot-50";

    private UUID insertDevice(UUID tenant) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO endpoint_devices
                  (id, tenant_id, org_id, hostname, os_type, status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)
                """, id, tenant, tenant, "host-" + id.toString().substring(0, 8),
                Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private JsonNode dnsEvidence() {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("class", "DNS_EDGE_MTLS");
        e.put("endpoint_host_hash", "deadbeef");
        e.put("edge_target", "edge.acik.com:8443");
        e.putNull("dns_error_code");
        e.putNull("tls_alert");
        e.putNull("mtls_peer_cert_fingerprint_prefix");
        e.put("observed_at", "2026-06-09T05:30:00Z");
        e.putNull("source");
        return e;
    }

    private EndpointRolloutFailure newActive(UUID tenant, UUID deviceId) {
        return EndpointRolloutFailure.newManual(UUID.randomUUID(), tenant, rollout, wave, deviceId,
                RolloutFailureClass.DNS_EDGE_MTLS, 3, ClassificationConfidence.HIGH,
                "manual:v1", "platform/edge operator", dnsEvidence(), Instant.now());
    }

    @Test
    void activeDuplicateIsRejectedByPartialUnique() {
        UUID device = insertDevice(orgA);
        failureRepository.saveAndFlush(newActive(orgA, device));
        EndpointRolloutFailure dup = newActive(orgA, device);
        assertThatThrownBy(() -> failureRepository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void freshObservationAfterResolutionGetsANewActiveRow() {
        UUID device = insertDevice(orgA);
        EndpointRolloutFailure first = failureRepository.saveAndFlush(newActive(orgA, device));
        // Resolve the first (slice-1a has no transition endpoint; mutate directly
        // to model the post-resolution state) so it leaves the active slot.
        jdbc.update("UPDATE endpoint_rollout_failure SET current_state = 'resolved', resolved_at = now() WHERE id = ?",
                first.getId());
        // A fresh failing observation inserts a NEW active row (no resurrection).
        EndpointRolloutFailure second = newActive(orgA, device);
        assertThat(failureRepository.saveAndFlush(second).getId()).isNotEqualTo(first.getId());
        assertThat(failureRepository.findActive(orgA, rollout, wave, device))
                .map(EndpointRolloutFailure::getId).contains(second.getId());
    }

    // 64-hex SHA-256-shaped fixture (the ck_erfe_actor_subject_hash CHECK).
    private static final String SUBJECT_HASH = "a".repeat(64);

    private UUID seedEvent() {
        UUID device = insertDevice(orgA);
        EndpointRolloutFailure failure = failureRepository.saveAndFlush(newActive(orgA, device));
        return eventRepository.saveAndFlush(
                EndpointRolloutFailureEvent.detectedManual(UUID.randomUUID(), failure, dnsEvidence(),
                        SUBJECT_HASH, Instant.now())).getId();
    }

    // The trigger raises inside the test transaction; on PG the first failing
    // statement aborts the whole tx, so UPDATE + DELETE need separate methods
    // (each @DataJpaTest method runs in its own rolled-back transaction).
    @Test
    void eventLedgerRejectsUpdate() {
        UUID eventId = seedEvent();
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE endpoint_rollout_failure_event SET source_signal = 'x' WHERE id = ?", eventId))
                .hasMessageContaining("append-only");
    }

    @Test
    void eventLedgerRejectsDelete() {
        UUID eventId = seedEvent();
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM endpoint_rollout_failure_event WHERE id = ?", eventId))
                .hasMessageContaining("append-only");
    }

    @Test
    void readsAreOrgScoped() {
        UUID device = insertDevice(orgA);
        EndpointRolloutFailure failure = failureRepository.saveAndFlush(newActive(orgA, device));
        assertThat(failureRepository.findByIdAndOrgId(failure.getId(), orgA)).isPresent();
        Optional<EndpointRolloutFailure> crossOrg =
                failureRepository.findByIdAndOrgId(failure.getId(), orgB);
        assertThat(crossOrg).as("a different org cannot read the failure by bare id").isEmpty();
    }
}
