package com.example.endpointadmin.migration;

import com.example.endpointadmin.model.CommandType;
import jakarta.persistence.EntityManager;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-030 regression guard for {@code V53__endpoint_update_agent_command_type.sql}.
 *
 * <p>The DB may recognize {@code UPDATE_AGENT}, but generic admin command
 * creation remains fail-closed in {@code EndpointAdminCommandService}. This
 * test pins only the schema + enum parity needed by the future dedicated
 * signed self-update release surface.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V53UpdateAgentCommandTypePostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String DEVICES = SCHEMA + ".endpoint_devices";
    private static final String COMMANDS = SCHEMA + ".endpoint_commands";

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
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema",
                () -> SCHEMA);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Test
    void v53LeavesSingleCanonicalCommandTypeCheckWithUpdateAgent() {
        List<String> checkNames = jdbc.queryForList("""
                SELECT conname
                FROM pg_catalog.pg_constraint
                WHERE conrelid = to_regclass(?)
                  AND contype = 'c'
                  AND pg_get_constraintdef(oid) LIKE '%command_type%'
                """, String.class, COMMANDS);
        assertThat(checkNames).containsExactly("ck_endpoint_commands_type");

        String definition = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_catalog.pg_constraint
                WHERE conrelid = to_regclass(?)
                  AND contype = 'c'
                  AND pg_get_constraintdef(oid) LIKE '%command_type%'
                """, String.class, COMMANDS);

        assertThat(definition)
                .contains("COLLECT_INVENTORY")
                .contains("INSTALL_SOFTWARE")
                .contains("UNINSTALL_SOFTWARE")
                .contains("UPDATE_AGENT");
    }

    @Test
    void updateAgentCommandTypeIsAcceptedByDbAndRoundTripsThroughJpaEnum() {
        UUID orgId = UUID.randomUUID();
        UUID deviceId = seedDevice(orgId);
        UUID commandId = seedCommand(orgId, deviceId, "UPDATE_AGENT");

        CommandType commandType = entityManager.createQuery(
                        "SELECT c.commandType FROM EndpointCommand c WHERE c.id = :id",
                        CommandType.class)
                .setParameter("id", commandId)
                .getSingleResult();

        assertThat(commandType).isEqualTo(CommandType.UPDATE_AGENT);
    }

    @Test
    void unknownCommandTypeStillFailsClosedAtDbLayer() {
        UUID orgId = UUID.randomUUID();
        UUID deviceId = seedDevice(orgId);

        assertThatThrownBy(() -> seedCommand(orgId, deviceId, "UNSIGNED_UPDATE_AGENT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID seedDevice(UUID orgId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + DEVICES + " ("
                        + "id, tenant_id, org_id, hostname, machine_fingerprint, "
                        + "status, os_type, os_version, agent_version, "
                        + "created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'ONLINE', 'WINDOWS', "
                        + "'Windows 11', '0.1.0-dev', ?, ?, 0)",
                id, orgId, orgId, "host-" + id, "fp-" + id, now, now);
        return id;
    }

    private UUID seedCommand(UUID orgId, UUID deviceId, String commandType) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + COMMANDS + " ("
                        + "id, tenant_id, org_id, device_id, command_type, "
                        + "idempotency_key, status, issued_by_subject, issued_at, "
                        + "created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', "
                        + "'self-update-test@example.com', ?, ?, ?, 0)",
                id, orgId, orgId, deviceId, commandType,
                "idem-" + id, now, now, now);
        return id;
    }
}
