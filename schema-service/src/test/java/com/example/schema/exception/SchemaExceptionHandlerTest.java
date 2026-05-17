package com.example.schema.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Q3 (handoff section 5 - Codex 019e335c): {@link SchemaExceptionHandler}
 * maps {@link SnapshotUnavailableException} to a controlled HTTP 503. The
 * body must be SANITIZED - the exception cause may carry SQL text / the JDBC
 * URL, and none of that may reach the HTTP response.
 */
class SchemaExceptionHandlerTest {

    private final SchemaExceptionHandler handler = new SchemaExceptionHandler();

    @Test
    void snapshotUnavailable_mapsTo503() {
        SnapshotUnavailableException ex = new SnapshotUnavailableException(
                "workcube_mikrolink", new RuntimeException("boom"));

        ResponseEntity<Map<String, String>> resp = handler.handleSnapshotUnavailable(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        Map<String, String> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("error")).isEqualTo("snapshot_unavailable");
        assertThat(body.get("schema")).isEqualTo("workcube_mikrolink");
        assertThat(body.get("reason")).contains("base table extraction");
    }

    @Test
    void responseBody_doesNotLeakCauseInternals() {
        // The cause deliberately carries source-DB internals — SQL text and a
        // JDBC URL. None of it may surface in the sanitized 503 body.
        RuntimeException cause = new RuntimeException(
                "The query has timed out: SELECT * FROM sys.tables; "
                + "jdbc:sqlserver://workcube-mssql:11433");
        SnapshotUnavailableException ex =
                new SnapshotUnavailableException("workcube_mikrolink", cause);

        Map<String, String> body = handler.handleSnapshotUnavailable(ex).getBody();

        assertThat(body).isNotNull();
        assertThat(body.values())
                .noneMatch(v -> v.contains("sys.tables"))
                .noneMatch(v -> v.contains("jdbc:sqlserver"))
                .noneMatch(v -> v.contains("timed out"));
    }
}
