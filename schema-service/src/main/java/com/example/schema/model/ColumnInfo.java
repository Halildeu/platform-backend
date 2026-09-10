package com.example.schema.model;

/**
 * One column of a table or view, engine-neutral.
 *
 * <p>{@code comment} and {@code label} arrived with the IFS catalog (gitops#3631).
 * {@code comment} is the raw dictionary comment as the source stores it
 * ({@code ALL_COL_COMMENTS.COMMENTS} on Oracle; not read on MSSQL yet).
 * {@code label} is the human-readable name a source carries for the column when it
 * has one — on IFS that is the {@code PROMPT=} entry of the structured comment —
 * and null where the source offers nothing better than the column name. Both are
 * nullable and additive: every existing constructor keeps working and serialises
 * them as {@code null}.
 */
public record ColumnInfo(
    String name,
    String dataType,
    int maxLength,
    Integer precision,          // sys.columns.precision (DECIMAL/NUMERIC/time)
    Integer scale,              // sys.columns.scale
    String collation,           // sys.columns.collation_name (string columns; null otherwise)
    boolean nullable,
    boolean identity,
    Long identitySeed,          // sys.identity_columns.seed_value (null if not identity)
    Long identityIncrement,     // sys.identity_columns.increment_value
    boolean pk,
    String defaultExpression,   // sys.default_constraints.definition (null if none)
    String computedExpression,  // sys.computed_columns.definition (null if not computed)
    boolean computedPersisted,  // sys.computed_columns.is_persisted
    boolean sparse,             // sys.columns.is_sparse
    int ordinal,
    String comment,             // raw dictionary comment (null when the source has none / is not read)
    String label                // human label derived from the source (IFS PROMPT=), else null
) {

    /** Pre-#3631 sixteen-field shape: no comment, no label. */
    public ColumnInfo(String name, String dataType, int maxLength,
                      Integer precision, Integer scale, String collation,
                      boolean nullable, boolean identity, Long identitySeed, Long identityIncrement,
                      boolean pk, String defaultExpression, String computedExpression,
                      boolean computedPersisted, boolean sparse,
                      int ordinal) {
        this(name, dataType, maxLength,
             precision, scale, collation,
             nullable, identity, identitySeed, identityIncrement,
             pk, defaultExpression, computedExpression, computedPersisted, sparse,
             ordinal, null, null);
    }

    public ColumnInfo(String name, String dataType, int maxLength,
                      boolean nullable, boolean identity, boolean pk, int ordinal) {
        this(name, dataType, maxLength,
             null, null, null,
             nullable, identity, null, null,
             pk, null, null, false, false,
             ordinal, null, null);
    }
}
