package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.CreateRolloutFailureRequest;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureSeedResponse;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.model.RolloutFailureState;
import com.example.endpointadmin.repository.EndpointRolloutFailureEventRepository;
import com.example.endpointadmin.repository.EndpointRolloutFailureRepository;
import com.example.endpointadmin.service.rolloutfailure.RolloutFailureEvidenceValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL behaviour of the manual seed write path (#527 slice-1b) on #528's
 * V60 schema: a seed creates one aggregate + one `detected` event, and a second
 * active seed for the same device/wave is rejected by the partial-unique index
 * ({@code ux_erf_active_device}) as a 409.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RolloutFailureQueueSeedServicePostgresIntegrationTest {

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

    private RolloutFailureQueueSeedService service() {
        return new RolloutFailureQueueSeedService(failureRepository, eventRepository,
                new RolloutFailureEvidenceValidator(MAPPER));
    }

    private final UUID tenant = UUID.randomUUID();
    private final String rollout = "rollout-2026-q3-agent-0.2.x";
    private final String wave = "wave-02-pilot-50";

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

    private CreateRolloutFailureRequest request(UUID deviceId) {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("class", "DNS_EDGE_MTLS");
        e.put("endpoint_host_hash", "deadbeef");
        e.put("edge_target", "edge.acik.com:8443");
        e.putNull("dns_error_code");
        e.putNull("tls_alert");
        e.putNull("mtls_peer_cert_fingerprint_prefix");
        e.put("observed_at", "2026-06-09T05:30:00Z");
        e.putNull("source");
        return new CreateRolloutFailureRequest(rollout, wave, deviceId,
                RolloutFailureClass.DNS_EDGE_MTLS, "high", e);
    }

    @Test
    void seedCreatesAggregateAndDetectedEvent() {
        UUID device = insertDevice();
        RolloutFailureSeedResponse resp = service().seedManual(tenant, "op@acik", request(device));

        assertThat(resp.currentState()).isEqualTo("new");
        assertThat(resp.classifierVersion()).isEqualTo("manual:v1");
        var failure = failureRepository.findByTenantIdAndId(tenant, resp.id()).orElseThrow();
        assertThat(failure.getCurrentState()).isEqualTo(RolloutFailureState.NEW);
        assertThat(failure.getRetryCount()).isZero();
        var events = eventRepository.findByTenantIdAndFailureIdOrderByCreatedAtAsc(tenant, resp.id());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType().wire()).isEqualTo("detected");
        assertThat(events.get(0).getActorType().wire()).isEqualTo("operator");
        // 64-hex SHA-256 actor subject hash, never the raw subject
        assertThat(events.get(0).getActorSubjectHash()).matches("[0-9a-f]{64}").isNotEqualTo("op@acik");
    }

    @Test
    void secondActiveSeedForSameDeviceWaveIs409() {
        UUID device = insertDevice();
        RolloutFailureQueueSeedService service = service();
        service.seedManual(tenant, "op@acik", request(device));
        assertThatThrownBy(() -> service.seedManual(tenant, "op@acik", request(device)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }
}
