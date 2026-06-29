package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.RolloutClassificationConfidence;
import com.example.endpointadmin.model.RolloutFailureActorType;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.model.RolloutFailureEventType;
import com.example.endpointadmin.model.RolloutFailureState;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointRolloutFailureEventRepository;
import com.example.endpointadmin.repository.EndpointRolloutFailureRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §9.2 slice-2a auto-ingest behaviour on real PG: a FAILED command result seeds
 * a queue item + a DETECTED/AUTO event; a replay of the same result is a no-op
 * (source_signal guard); a distinct later result coalesces onto the active item
 * (no second aggregate, a RETRY observation event); an unclassifiable result is
 * skipped. The command-result repository is mocked (the result chain isn't the
 * subject); the failure/event repositories are real.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RolloutFailureAutoIngestServicePostgresIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
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

    @Autowired EndpointRolloutFailureRepository failureRepository;
    @Autowired EndpointRolloutFailureEventRepository eventRepository;
    @Autowired JdbcTemplate jdbc;

    private final EndpointCommandResultRepository resultRepository = mock(EndpointCommandResultRepository.class);

    private RolloutFailureAutoIngestService service() {
        return new RolloutFailureAutoIngestService(resultRepository, failureRepository, eventRepository,
                new RolloutFailureClassifier(MAPPER), new RolloutFailureEvidenceValidator(MAPPER));
    }

    private final UUID tenant = UUID.randomUUID();

    private UUID insertDevice() {
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

    private UUID mockMsiResult(int exitCode) {
        UUID resultId = UUID.randomUUID();
        EndpointCommandResult r = mock(EndpointCommandResult.class);
        when(r.getId()).thenReturn(resultId);
        when(r.getResultStatus()).thenReturn(CommandResultStatus.FAILED);
        when(r.getErrorCode()).thenReturn("INSTALL_FAILED_MSI");
        when(r.getExitCode()).thenReturn(exitCode);
        when(r.getErrorMessage()).thenReturn("redacted error");
        when(r.getResultPayload()).thenReturn(Map.of());
        when(resultRepository.findById(resultId)).thenReturn(Optional.of(r));
        return resultId;
    }

    private CommandResultFailedEvent event(UUID device, UUID resultId) {
        return new CommandResultFailedEvent(tenant, device, resultId, CommandType.INSTALL_SOFTWARE);
    }

    @Test
    void autoSeedCreatesAggregateAndDetectedAutoEvent() {
        UUID device = insertDevice();
        UUID resultId = mockMsiResult(1627);
        assertThat(service().ingest(event(device, resultId))).isTrue();

        var rows = failureRepository.findByTenantIdAndRolloutIdAndWaveIdOrderByLastTransitionAtDesc(
                tenant, "cmd-result-auto:INSTALL_SOFTWARE", "INSTALLER_MSI");
        assertThat(rows).hasSize(1);
        var f = rows.get(0);
        assertThat(f.getCurrentClass()).isEqualTo(RolloutFailureClass.INSTALLER_MSI);
        assertThat(f.getCurrentState()).isEqualTo(RolloutFailureState.NEW);
        assertThat(f.getClassifierVersion()).isEqualTo("auto:command-result:v1");
        assertThat(f.getRetryCount()).isZero();

        var events = eventRepository.findByTenantIdAndFailureIdOrderByCreatedAtAsc(tenant, f.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(RolloutFailureEventType.DETECTED);
        assertThat(events.get(0).getActorType()).isEqualTo(RolloutFailureActorType.AUTO);
        assertThat(events.get(0).getSourceSignal()).isEqualTo("command_result:" + resultId);
    }

    @Test
    void replayOfSameResultIsNoOp() {
        UUID device = insertDevice();
        UUID resultId = mockMsiResult(1627);
        RolloutFailureAutoIngestService s = service();
        assertThat(s.ingest(event(device, resultId))).isTrue();
        assertThat(s.ingest(event(device, resultId))).isFalse(); // replay guard

        var f = failureRepository.findByTenantIdAndRolloutIdAndWaveIdOrderByLastTransitionAtDesc(
                tenant, "cmd-result-auto:INSTALL_SOFTWARE", "INSTALLER_MSI").get(0);
        assertThat(eventRepository.findByTenantIdAndFailureIdOrderByCreatedAtAsc(tenant, f.getId()))
                .hasSize(1); // no second ledger row for the same source result
    }

    @Test
    void replayOfSameClassifiedSourceSignalIsNoOp() {
        UUID device = insertDevice();
        UUID certId = UUID.randomUUID();
        String sourceSignal = "cert_identity:active_cert_expired:" + certId;
        Instant observedAt = Instant.parse("2026-06-29T08:00:00Z");
        RolloutFailureAutoIngestService s = service();
        RolloutFailureClassifier.Classified classified = certIdentityClassified(device, certId);

        assertThat(s.ingestClassified(
                tenant,
                device,
                "cert-identity-auto:active-cert-expired",
                RolloutFailureClass.CERT_IDENTITY.name(),
                classified,
                "auto:cert-identity:v1",
                sourceSignal,
                observedAt)).isTrue();
        assertThat(s.ingestClassified(
                tenant,
                device,
                "cert-identity-auto:active-cert-expired",
                RolloutFailureClass.CERT_IDENTITY.name(),
                classified,
                "auto:cert-identity:v1",
                sourceSignal,
                observedAt.plusSeconds(900))).isFalse();

        var rows = failureRepository.findByTenantIdAndRolloutIdAndWaveIdOrderByLastTransitionAtDesc(
                tenant, "cert-identity-auto:active-cert-expired", RolloutFailureClass.CERT_IDENTITY.name());
        assertThat(rows).hasSize(1);
        assertThat(eventRepository.findByTenantIdAndFailureIdOrderByCreatedAtAsc(tenant, rows.get(0).getId()))
                .hasSize(1);
    }

    @Test
    void distinctResultOnNewItemCoalescesWithoutInvalidEvent() {
        UUID device = insertDevice();
        RolloutFailureAutoIngestService s = service();
        s.ingest(event(device, mockMsiResult(1627)));
        boolean coalesced = s.ingest(event(device, mockMsiResult(1602))); // distinct result, same device/class
        assertThat(coalesced).isTrue();

        var rows = failureRepository.findByTenantIdAndRolloutIdAndWaveIdOrderByLastTransitionAtDesc(
                tenant, "cmd-result-auto:INSTALL_SOFTWARE", "INSTALLER_MSI");
        assertThat(rows).as("no second active aggregate — coalesced").hasSize(1);
        // current_state=NEW: NEW->NEW is not a valid transition (contract §4), so a
        // repeat observation bumps last-observed only — NO second ledger event.
        var events = eventRepository.findByTenantIdAndFailureIdOrderByCreatedAtAsc(tenant, rows.get(0).getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo(RolloutFailureEventType.DETECTED);
    }

    // The retrying->retrying coalesce self-loop is a forward-looking defensive
    // branch (no transition mechanism sets RETRYING in slice-2a) — covered by the
    // mocked unit test RolloutFailureAutoIngestServiceTest, which avoids the
    // REQUIRES_NEW cross-transaction visibility limitation of an in-tx jdbc UPDATE.

    @Test
    void unclassifiableResultIsSkipped() {
        UUID device = insertDevice();
        UUID resultId = UUID.randomUUID();
        EndpointCommandResult r = mock(EndpointCommandResult.class);
        when(r.getId()).thenReturn(resultId);
        when(r.getResultStatus()).thenReturn(CommandResultStatus.FAILED);
        when(r.getErrorCode()).thenReturn("WEIRD_UNKNOWN");
        when(r.getExitCode()).thenReturn(null);
        when(r.getErrorMessage()).thenReturn(null);
        when(r.getResultPayload()).thenReturn(Map.of());
        when(resultRepository.findById(resultId)).thenReturn(Optional.of(r));

        assertThat(service().ingest(new CommandResultFailedEvent(
                tenant, device, resultId, CommandType.COLLECT_INVENTORY))).isFalse();
        assertThat(failureRepository.findByTenantIdAndRolloutIdAndWaveIdOrderByLastTransitionAtDesc(
                tenant, "cmd-result-auto:COLLECT_INVENTORY", "INSTALLER_MSI")).isEmpty();
    }

    private RolloutFailureClassifier.Classified certIdentityClassified(UUID device, UUID certId) {
        var evidence = MAPPER.createObjectNode();
        evidence.put("class", RolloutFailureClass.CERT_IDENTITY.name());
        evidence.put("device_id", device.toString());
        evidence.put("cert_fingerprint_prefix", "0123456789abcdef");
        evidence.put("issuer_id", "issuer-sha256-0123456789abcdef");
        evidence.put("subject_san_hash", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        evidence.put("enrollment_status", "CERT_EXPIRED");
        evidence.put("cert_not_before", "2026-05-29T08:00:00Z");
        evidence.put("cert_not_after", "2026-06-28T08:00:00Z");
        evidence.put("audit_event_id", "machine-cert:" + certId);
        return new RolloutFailureClassifier.Classified(
                RolloutFailureClass.CERT_IDENTITY,
                RolloutClassificationConfidence.HIGH,
                evidence);
    }
}
