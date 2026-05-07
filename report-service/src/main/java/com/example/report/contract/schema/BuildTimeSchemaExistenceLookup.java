package com.example.report.contract.schema;

import com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup.CoverageStatus;

/**
 * Phase 2 Program 2c (Codex iter-18 §2c absorb, thread 019e0119) — unified
 * schema truth existence lookup for RC-004.
 *
 * <p>Routes a {@code (schema, table, column)} probe between:
 * <ul>
 *   <li>{@link BuildTimeSchemaTruthLookup}: canonical reference snapshot
 *       (1509 tables, HR + global tables; build-time governance artifact
 *       at {@code classpath:schema/workcube-schema.json})</li>
 *   <li>{@link BuildTimeYearlySchemaCoverageLookup}: yearly partition
 *       coverage artifact (CARI_ROWS, INVOICE_ROW, etc. per year+tenant;
 *       built by 2b YearlySchemaCoverageExporter)</li>
 * </ul>
 *
 * <p>Routing strategy: canonical first (covers HR/static tables), then
 * yearly fallback. If canonical doesn't include the table AND yearly
 * artifact is empty (pre-deployment), returns {@code NOT_COVERED} with
 * a graceful-degradation hint via {@link #yearlyArtifactPresent()}.
 *
 * <p>Build-time only: no {@code @Component}; explicit constructor
 * injection. Both delegated lookups must already have their respective
 * load methods invoked.
 */
public final class BuildTimeSchemaExistenceLookup {

    private final BuildTimeSchemaTruthLookup canonical;
    private final BuildTimeYearlySchemaCoverageLookup yearly;

    public BuildTimeSchemaExistenceLookup(BuildTimeSchemaTruthLookup canonical,
                                           BuildTimeYearlySchemaCoverageLookup yearly) {
        this.canonical = canonical;
        this.yearly = yearly;
    }

    /**
     * Probe column existence. Resolution order:
     * <ol>
     *   <li>Canonical snapshot: if table is present, decide via
     *       {@code columnExists()} (PRESENT or COLUMN_MISSING).</li>
     *   <li>Yearly coverage: if artifact has any schemas, delegate
     *       {@code lookup()} (PRESENT/COLUMN_MISSING/NOT_COVERED).</li>
     *   <li>Graceful degradation: yearly artifact empty (schemaCount=0)
     *       → return NOT_COVERED. Caller (RC-004) may skip the existence
     *       check during pre-deployment rollout.</li>
     * </ol>
     */
    public CoverageStatus lookup(String sourceSchema, String table, String column) {
        if (canonical != null && canonical.tableExists(table)) {
            return canonical.columnExists(table, column)
                    ? CoverageStatus.PRESENT
                    : CoverageStatus.COLUMN_MISSING;
        }
        if (yearly != null && yearly.schemaCount() > 0) {
            return yearly.lookup(sourceSchema, table, column);
        }
        return CoverageStatus.NOT_COVERED;
    }

    /**
     * Whether the yearly coverage artifact has been deployed (schemaCount > 0).
     * RC-004 uses this to decide between graceful-skip during pre-deployment
     * and active enforcement when the artifact ships.
     */
    public boolean yearlyArtifactPresent() {
        return yearly != null && yearly.schemaCount() > 0;
    }

    /** Whether the canonical snapshot has been loaded. */
    public boolean canonicalArtifactPresent() {
        return canonical != null && canonical.tableCount() > 0;
    }

    /** Combined "is enforcement active" — either artifact is live. */
    public boolean enforcementActive() {
        return canonicalArtifactPresent() || yearlyArtifactPresent();
    }
}
