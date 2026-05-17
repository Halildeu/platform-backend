package com.example.schema.exception;

/**
 * Q3 (handoff section 5 - Codex 019e335c): raised when
 * {@code SchemaSnapshotService.buildSnapshot} cannot complete its mandatory
 * base table extraction ({@code SchemaExtractService.extractTables} -&gt;
 * {@code extractBaseTables}).
 *
 * <p>Base table extraction is genuinely fatal: without it there is no
 * snapshot at all. After P1 the column enrichment + the eight B1 inventory
 * reads are non-fatal (they degrade the snapshot in place), so ONLY a base
 * extraction failure reaches here.
 *
 * <p>Wrapping that failure in a domain exception lets the web layer
 * ({@code SchemaExceptionHandler}) answer a controlled HTTP 503 instead of a
 * generic 500. The {@code cause} may carry SQL text / JDBC details and is for
 * server-side logging only - it is never surfaced in the HTTP response.
 */
public class SnapshotUnavailableException extends RuntimeException {

    private final String schema;

    public SnapshotUnavailableException(String schema, Throwable cause) {
        super("Schema snapshot unavailable for '" + schema
                + "': base table extraction failed", cause);
        this.schema = schema;
    }

    /** The schema whose snapshot could not be built. Safe to surface. */
    public String schema() {
        return schema;
    }
}
