package com.example.report.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Phase 2 Program 2a — TenantGuardExceptionHandler tests.
 *
 * <p>Codex iter-11 §2a-AGREE absorb (thread 019e0119): public failure
 * contract — JSON body shape, HTTP status, attemptedSchemas serialization
 * — must be locked at this layer. Direct handler unit (no @WebMvcTest)
 * is sufficient.
 */
class TenantGuardExceptionHandlerTest {

    private TenantGuardExceptionHandler handler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        handler = new TenantGuardExceptionHandler(mapper);
    }

    @Test
    void handleTenantSelectionRequired_returns400StableBody() {
        TenantSelectionRequiredException ex = new TenantSelectionRequiredException(
                "fin-cari-hareketler", "Yearly report 'fin-cari-hareketler' requires an explicit COMPANY scope");

        ResponseEntity<JsonNode> response = handler.handleTenantSelectionRequired(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("error").asText()).isEqualTo("tenant_selection_required");
        assertThat(body.get("reportKey").asText()).isEqualTo("fin-cari-hareketler");
        assertThat(body.get("message").asText())
                .contains("requires an explicit COMPANY scope");
        assertThat(body.get("hint").asText())
                .contains("X-Company-Id")
                .contains("COMPANY scope");
    }

    @Test
    void handleSchemaResolverMiss_returns503StableBodyWithAttemptedSchemas() {
        SchemaResolverMissException ex = new SchemaResolverMissException(
                "fin-cari-hareketler",
                List.of("workcube_mikrolink_2026_35", "workcube_mikrolink_2025_35"),
                "no matching schema");

        ResponseEntity<JsonNode> response = handler.handleSchemaResolverMiss(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("error").asText()).isEqualTo("schema_resolver_miss");
        assertThat(body.get("reportKey").asText()).isEqualTo("fin-cari-hareketler");
        assertThat(body.get("message").asText()).contains("no matching schema");
        assertThat(body.get("attemptedSchemas").isArray()).isTrue();
        assertThat(body.get("attemptedSchemas")).hasSize(2);
        assertThat(body.get("attemptedSchemas").get(0).asText())
                .isEqualTo("workcube_mikrolink_2026_35");
        assertThat(body.get("attemptedSchemas").get(1).asText())
                .isEqualTo("workcube_mikrolink_2025_35");
        assertThat(body.get("hint").asText()).contains("yearly partition");
    }

    @Test
    void handleSchemaResolverMiss_emptyAttemptedSchemas_serializesAsEmptyArray() {
        SchemaResolverMissException ex = new SchemaResolverMissException(
                "fin-x", List.of(), "miss");

        ResponseEntity<JsonNode> response = handler.handleSchemaResolverMiss(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        JsonNode body = response.getBody();
        assertThat(body.get("attemptedSchemas").isArray()).isTrue();
        assertThat(body.get("attemptedSchemas")).isEmpty();
    }

    @Test
    void handleTenantSelectionRequired_nullReportKey_serializesAsNullField() {
        // Defensive: handler must not NPE on partially-constructed exception.
        TenantSelectionRequiredException ex = new TenantSelectionRequiredException(
                null, "missing");

        ResponseEntity<JsonNode> response = handler.handleTenantSelectionRequired(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("reportKey").isNull()).isTrue();
    }
}
