package com.example.report.query;

import java.util.List;

/**
 * Phase 2 Program 2a — Yearly schema resolution miss.
 *
 * <p>Codex iter-10 §2a-AGREE absorb (thread 019e0119): yearly partition
 * resolver couldn't find any matching {@code workcube_mikrolink_<year>_<companyId>}
 * schema in sys.schemas. Silent {@code def.sourceSchema()} fallback removed;
 * this exception triggers a deterministic 503 {@code schema_resolver_miss}
 * so the caller can retry / surface a clearer error rather than silently
 * querying canonical reference data.
 */
public class SchemaResolverMissException extends RuntimeException {

    private final String reportKey;
    private final List<String> attemptedSchemas;

    public SchemaResolverMissException(String reportKey, List<String> attemptedSchemas, String message) {
        super(message);
        this.reportKey = reportKey;
        this.attemptedSchemas = attemptedSchemas == null ? List.of() : List.copyOf(attemptedSchemas);
    }

    public String reportKey() {
        return reportKey;
    }

    public List<String> attemptedSchemas() {
        return attemptedSchemas;
    }
}
