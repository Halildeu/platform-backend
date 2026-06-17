package com.example.auditconsumer.schema;

import com.example.auditconsumer.audit.AuditIntegrityVerifier;
import com.example.auditconsumer.model.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — the FIX proof for the non-public
 * schema native-INSERT search_path bug (gitops PR #1648; Codex thread 019ed4bb).
 *
 * <p>Same production schema layout as the reproduction (Flyway builds the table in
 * the NON-public {@code audit_event} schema), but the runtime datasource URL now
 * carries {@code ?currentSchema=audit_event} — the exact fix gitops PR #1648
 * applied to the consumer datasource. The connection {@code search_path} now
 * includes {@code audit_event}, so the unqualified native
 * {@code INSERT INTO audit_event} resolves to {@code audit_event.audit_event} and
 * the real producer→stream→consumer→persist chain lands the row, hash-chained.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuditEventSchemaSearchPathFixPostgresIntegrationTest
        extends AbstractAuditEventSchemaSearchPathSupport {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("audit_event")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registerCommonProperties(registry, POSTGRES, REDIS);
        // THE FIX (gitops PR #1648): append ?currentSchema=audit_event so the
        // connection search_path includes the non-public schema and the
        // unqualified native INSERT resolves to audit_event.audit_event.
        registry.add("spring.datasource.url", () -> {
            String base = POSTGRES.getJdbcUrl();
            return base + (base.contains("?") ? "&" : "?") + "currentSchema=" + AUDIT_SCHEMA;
        });
    }

    private static final long FIX_TENANT = 5201L;

    @Test
    void chunkAdmissionRejectedIsConsumedAndPersistedIntoNonPublicSchemaWithHashChain() {
        long ts = 1_700_003_000_000L;
        xadd(rejectedEvent(FIX_TENANT, "fix-sess", 1, 413, "OVERSIZE", null, ts));

        // (3) The real producer→stream→consumer→persist chain now lands the row —
        //     the unqualified native INSERT resolves thanks to currentSchema.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(rowCount(FIX_TENANT)).isEqualTo(1));

        // Persisted specifically INTO the non-public `audit_event` schema (raw,
        // schema-qualified read — independent of the JPA finder).
        Long inSchema = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event.audit_event WHERE tenant_id = ?", Long.class, FIX_TENANT);
        assertThat(inSchema).isEqualTo(1L);

        // Production layout invariant holds: present in audit_event, absent from
        // public; and — the observable fix — the UNQUALIFIED name now resolves
        // (currentSchema put audit_event on the search_path), the direct contrast
        // to the bug context where the same lookup is null.
        assertThat(regclass("public.audit_event")).isNull();
        assertThat(regclass("audit_event.audit_event")).isNotNull();
        assertThat(regclass("audit_event"))
                .as("currentSchema=audit_event must put the table on the search_path")
                .isNotNull();

        // Hash-chain columns are populated (BE-016 chain): genesis prev=null, a
        // 64-hex lowercase SHA-256 entry hash, alg + version pinned.
        AuditEvent row = repository.findByTenantIdOrderBySeqAsc(FIX_TENANT).get(0);
        assertThat(row.getTenantId()).isEqualTo(FIX_TENANT);
        assertThat(row.getEventType()).isEqualTo(EVENT_TYPE);
        assertThat(row.getSessionId()).isEqualTo("fix-sess");
        assertThat(row.getHttpStatus()).isEqualTo(413);
        assertThat(row.getRejectionCode()).isEqualTo("OVERSIZE");
        assertThat(row.getUserId()).isEqualTo(7L);
        assertThat(row.getIngestedAt()).isNotNull();
        assertThat(row.getPrevHash()).isNull();
        assertThat(row.getEntryHash()).isNotNull().matches("[0-9a-f]{64}");
        assertThat(row.getEntryHashAlg()).isEqualTo("SHA-256");
        assertThat(row.getEntryHashVersion()).isEqualTo(1);

        // The hash-chain verifier accepts the persisted chain...
        AuditIntegrityVerifier.Result verified = verifier.verifyTenant(FIX_TENANT);
        assertThat(verified.valid()).isTrue();
        assertThat(verified.checkedCount()).isEqualTo(1);

        // ...and the entry was ACKed (the PEL drains to 0) — no audit loss, no
        // stuck pending entry (the inverse of the bug-context symptom).
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(pendingCount()).isZero());
    }
}
