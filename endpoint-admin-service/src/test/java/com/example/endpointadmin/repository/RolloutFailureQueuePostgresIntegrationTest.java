package com.example.endpointadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.dto.v1.admin.WaveFailureExportResponse;
import com.example.endpointadmin.dto.v1.admin.WaveFailureQueueReportResponse;
import com.example.endpointadmin.model.EndpointRolloutWaveMetricsSnapshot;
import com.example.endpointadmin.model.EndpointRolloutFailure;
import com.example.endpointadmin.model.EndpointRolloutFailureEvent;
import com.example.endpointadmin.model.RolloutClassificationConfidence;
import com.example.endpointadmin.model.RolloutFailureActorType;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.model.RolloutFailureEventType;
import com.example.endpointadmin.model.RolloutFailureState;
import com.example.endpointadmin.service.FailedDeviceQueueSchemaValidator;
import com.example.endpointadmin.service.RolloutFailureQueueReadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * #527 slice-1 — V60 failed-device rollout queue foundation PG contract (Codex
 * 019eaaf3 minimums). Proves: valid fixture validates + persists + reads;
 * off-allowlist evidence is rejected by the schema validator; the active-device
 * partial unique index rejects a second active item but ALLOWS a new active item
 * once the prior is resolved; the event ledger is append-only (UPDATE/DELETE
 * rejected); @Version gives optimistic locking; and the wave report counts
 * active items WITHOUT claiming an enforced stop-line.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RolloutFailureQueuePostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";

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
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    private static final FailedDeviceQueueSchemaValidator VALIDATOR =
            new FailedDeviceQueueSchemaValidator(new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));

    @Autowired private EndpointRolloutFailureRepository failureRepository;
    @Autowired private EndpointRolloutFailureEventRepository eventRepository;
    @Autowired private com.example.endpointadmin.repository.EndpointRolloutWaveMetricsSnapshotRepository snapshotRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;

    @Test
    void validFixturePersistsAndReadsBackWithOrderedEvents() {
        UUID tenant = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        EndpointRolloutFailure agg = seedActive(tenant, "rollout-a", "wave-1", device, RolloutFailureState.RETRYING);

        EndpointRolloutFailure found = failureRepository.findByTenantIdAndId(tenant, agg.getId()).orElseThrow();
        assertThat(found.getOrgId()).isEqualTo(tenant); // org canonical
        assertThat(found.getEvidenceRedacted()).containsEntry("class", "SERVICE_HMAC_MODE");

        var events = eventRepository.findByTenantIdAndFailureIdOrderByCreatedAtAsc(tenant, agg.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(RolloutFailureEventType.DETECTED);
        assertThat(events.get(0).getToState()).isEqualTo(RolloutFailureState.NEW);
    }

    @Test
    void schemaValidatorRejectsOffAllowlistEvidence() {
        Map<String, Object> bad = hmacEvidence(UUID.randomUUID());
        bad.put("raw_last_error", "secret leaked");
        assertThatThrownBy(() -> VALIDATOR.validateEvidence(RolloutFailureClass.SERVICE_HMAC_MODE, bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partialUniqueRejectsSecondActiveItemForSameDeviceWave() {
        UUID tenant = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        seedActive(tenant, "rollout-b", "wave-1", device, RolloutFailureState.NEW);
        assertThatThrownBy(() -> seedActive(tenant, "rollout-b", "wave-1", device, RolloutFailureState.RETRYING))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aNewActiveItemIsAllowedOnceThePriorIsResolved() {
        UUID tenant = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        EndpointRolloutFailure first = seedActive(tenant, "rollout-c", "wave-1", device, RolloutFailureState.NEW);
        first.setCurrentState(RolloutFailureState.RESOLVED);
        first.setResolvedAt(Instant.now());
        failureRepository.saveAndFlush(first);
        // a fresh failing observation on the same device/wave is now allowed.
        EndpointRolloutFailure second = seedActive(tenant, "rollout-c", "wave-1", device, RolloutFailureState.NEW);
        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    @Test
    void eventLedgerIsAppendOnly() {
        UUID tenant = UUID.randomUUID();
        EndpointRolloutFailure agg = seedActive(tenant, "rollout-d", "wave-1", UUID.randomUUID(), RolloutFailureState.NEW);
        UUID eventId = eventRepository.findByTenantIdAndFailureIdOrderByCreatedAtAsc(tenant, agg.getId())
                .get(0).getId();
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE endpoint_admin_service.endpoint_rollout_failure_event SET source_signal = 'x' WHERE id = ?", eventId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM endpoint_admin_service.endpoint_rollout_failure_event WHERE id = ?", eventId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void optimisticLockOnConcurrentUpdate() {
        UUID tenant = UUID.randomUUID();
        EndpointRolloutFailure agg = seedActive(tenant, "rollout-e", "wave-1", UUID.randomUUID(), RolloutFailureState.NEW);
        // Simulate a concurrent writer bumping the row version out from under us.
        jdbc.update("UPDATE endpoint_admin_service.endpoint_rollout_failure SET version = version + 1 WHERE id = ?",
                agg.getId());
        agg.setRetryCount(agg.getRetryCount() + 1);
        assertThatThrownBy(() -> failureRepository.saveAndFlush(agg))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void waveReportCountsActiveWithoutAnEnforcedStopLine() {
        UUID tenant = UUID.randomUUID();
        seedActive(tenant, "rollout-f", "wave-1", UUID.randomUUID(), RolloutFailureState.NEW);
        seedActive(tenant, "rollout-f", "wave-1", UUID.randomUUID(), RolloutFailureState.RETRYING);
        em.flush();
        em.clear();

        RolloutFailureQueueReadService read = new RolloutFailureQueueReadService(failureRepository, eventRepository,
                new com.example.endpointadmin.service.rolloutfailure.WaveStopLineEvaluator(snapshotRepository));
        WaveFailureQueueReportResponse report = read.waveReport(tenant, "rollout-f", "wave-1", Instant.now());

        assertThat(report.activeCount()).isEqualTo(2);
        assertThat(report.activeCountByClass()).containsEntry("SERVICE_HMAC_MODE", 2L);
        assertThat(report.thresholdEvaluation().available()).isFalse();
        assertThat(report.thresholdEvaluation().enforcementFlag()).isFalse();
        assertThat(report.thresholdEvaluation().reason()).isEqualTo("metrics_snapshot_missing");
    }

    @Test
    void waveExportIsContractShapedWhenMetricsSnapshotExists() {
        UUID tenant = UUID.randomUUID();
        EndpointRolloutFailure first = seedActive(tenant, "rollout-h", "wave-1", UUID.randomUUID(),
                RolloutFailureState.NEW);
        first.setEscalationIssueUrl("https://github.com/Halildeu/platform-backend/issues/9999");
        failureRepository.saveAndFlush(first);
        seedActive(tenant, "rollout-h", "wave-1", UUID.randomUUID(), RolloutFailureState.RETRYING);
        seedSnapshot(tenant, "rollout-h", "wave-1", 10, 800, 17);
        em.flush();
        em.clear();

        RolloutFailureQueueReadService read = new RolloutFailureQueueReadService(failureRepository, eventRepository,
                new com.example.endpointadmin.service.rolloutfailure.WaveStopLineEvaluator(snapshotRepository));
        WaveFailureExportResponse export = read.waveExport(tenant, "rollout-h", "wave-1",
                Instant.parse("2026-06-29T05:30:00Z"));

        assertThat(export.activeWaveSize()).isEqualTo(10);
        assertThat(export.fleetSize()).isEqualTo(800);
        assertThat(export.waveFailedCount()).isEqualTo(2L);
        assertThat(export.stopLineStatus()).isEqualTo("stop_expansion");
        assertThat(export.perClassCounts())
                .containsEntry("SERVICE_HMAC_MODE", 2L)
                .containsEntry("DNS_EDGE_MTLS", 0L)
                .containsEntry("CERT_IDENTITY", 0L)
                .containsEntry("INSTALLER_MSI", 0L)
                .containsEntry("BACKEND_RESULT_SUBMIT", 0L)
                .containsEntry("EDR_NETWORK", 0L);
        assertThat(export.sampleItems()).hasSize(2)
                .allSatisfy(item -> assertThat(item.orgId()).isEqualTo(tenant));
        assertThat(export.escalationIssueRefs())
                .containsExactly("https://github.com/Halildeu/platform-backend/issues/9999");
        assertThat(export.enforcement().liveIngest()).isTrue();
        assertThat(export.enforcement().thresholdEvaluator()).isTrue();
        assertThat(export.enforcement().githubEscalationGenerator()).isTrue();
        assertThat(export.enforcement().deploymentEnforcementActive()).isFalse();
        VALIDATOR.validateWaveFailureReport(export);
    }

    @Test
    void waveExportFailsClosedWithoutMetricsSnapshot() {
        UUID tenant = UUID.randomUUID();
        seedActive(tenant, "rollout-i", "wave-1", UUID.randomUUID(), RolloutFailureState.NEW);
        em.flush();
        em.clear();

        RolloutFailureQueueReadService read = new RolloutFailureQueueReadService(failureRepository, eventRepository,
                new com.example.endpointadmin.service.rolloutfailure.WaveStopLineEvaluator(snapshotRepository));
        assertThatThrownBy(() -> read.waveExport(tenant, "rollout-i", "wave-1", Instant.now()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("metrics_snapshot_missing");
    }

    @Test
    void eventCannotAttachToAParentAggregateInAnotherOrg() {
        UUID tenantA = UUID.randomUUID();
        EndpointRolloutFailure agg = seedActive(tenantA, "rollout-g", "wave-1", UUID.randomUUID(),
                RolloutFailureState.NEW);
        // an event under a DIFFERENT org pointing at org-A's aggregate must be rejected
        // by the org-composite FK (Codex 019eaaf3 must-fix 2).
        EndpointRolloutFailureEvent crossOrg = new EndpointRolloutFailureEvent();
        crossOrg.setId(UUID.randomUUID());
        crossOrg.setTenantId(UUID.randomUUID()); // org B != org A
        crossOrg.setFailureId(agg.getId());
        crossOrg.setEventType(RolloutFailureEventType.TRANSITION);
        crossOrg.setFromState(RolloutFailureState.NEW);
        crossOrg.setToState(RolloutFailureState.RETRYING);
        crossOrg.setFailureClass(RolloutFailureClass.SERVICE_HMAC_MODE);
        crossOrg.setRedactedEvidence(hmacEvidence(UUID.randomUUID()));
        crossOrg.setActorType(RolloutFailureActorType.SYSTEM);
        assertThatThrownBy(() -> eventRepository.saveAndFlush(crossOrg))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- fixture writer (goes through the SAME validator as a future ingest) ----

    private EndpointRolloutFailure seedActive(UUID tenant, String rolloutId, String waveId, UUID device,
                                              RolloutFailureState state) {
        Map<String, Object> evidence = hmacEvidence(device);
        VALIDATOR.validateEvidence(RolloutFailureClass.SERVICE_HMAC_MODE, evidence); // fail-closed before persist

        Instant now = Instant.now();
        EndpointRolloutFailure agg = new EndpointRolloutFailure();
        agg.setId(UUID.randomUUID());
        agg.setTenantId(tenant);
        agg.setRolloutId(rolloutId);
        agg.setWaveId(waveId);
        agg.setDeviceId(device);
        agg.setCurrentClass(RolloutFailureClass.SERVICE_HMAC_MODE);
        agg.setCurrentState(state);
        agg.setRetryCount(0);
        agg.setMaxRetries(2);
        agg.setFirstDetectedAt(now);
        agg.setLastObservedAt(now);
        agg.setLastTransitionAt(now);
        agg.setEvidenceRedacted(evidence);
        agg.setOwnerRole("endpoint-agent operator");
        agg.setStopLineContribution(Boolean.TRUE);
        agg.setClassificationConfidence(RolloutClassificationConfidence.HIGH);
        agg.setClassifierVersion("test-fixture");
        EndpointRolloutFailure saved = failureRepository.saveAndFlush(agg);

        EndpointRolloutFailureEvent ev = new EndpointRolloutFailureEvent();
        ev.setId(UUID.randomUUID());
        ev.setTenantId(tenant);
        ev.setFailureId(saved.getId());
        ev.setEventType(RolloutFailureEventType.DETECTED);
        ev.setFromState(null);
        ev.setToState(RolloutFailureState.NEW);
        ev.setFailureClass(RolloutFailureClass.SERVICE_HMAC_MODE);
        ev.setSourceSignal("test-fixture");
        ev.setRedactedEvidence(evidence);
        ev.setActorType(RolloutFailureActorType.AUTO);
        ev.setClassificationConfidence(RolloutClassificationConfidence.HIGH);
        eventRepository.saveAndFlush(ev);
        return saved;
    }

    private void seedSnapshot(UUID tenant, String rolloutId, String waveId, int activeWaveSize,
                              int fleetSize, int stale24hCount) {
        EndpointRolloutWaveMetricsSnapshot snapshot = new EndpointRolloutWaveMetricsSnapshot();
        snapshot.setId(UUID.randomUUID());
        snapshot.setTenantId(tenant);
        snapshot.setRolloutId(rolloutId);
        snapshot.setWaveId(waveId);
        snapshot.setActiveWaveSize(activeWaveSize);
        snapshot.setFleetSize(fleetSize);
        snapshot.setStale24hCount(stale24hCount);
        snapshot.setCapturedAt(Instant.parse("2026-06-29T05:29:00Z"));
        snapshot.setSourceSnapshotId("it-fdq-export-1");
        snapshotRepository.saveAndFlush(snapshot);
    }

    private static Map<String, Object> hmacEvidence(UUID device) {
        Map<String, Object> e = new HashMap<>();
        e.put("class", "SERVICE_HMAC_MODE");
        e.put("device_id", device.toString());
        e.put("service_state", "running");
        e.put("agent_mode", "hmac");
        e.put("hmac_error_code", "HMAC_CONN_RESET");
        e.put("last_heartbeat_at", null);
        e.put("command_id", null);
        e.put("agent_version", "0.2.0");
        return e;
    }
}
