package com.example.commonexport;

/**
 * Thrown by the streaming exporters when a hard row cap is exceeded mid-stream.
 *
 * <p>Distinct from a generic export failure: it signals "the in-scope result
 * set is larger than the maximum allowed export size", which a controller
 * should surface as a {@code 400 Bad Request} (narrow the scope) rather than a
 * {@code 500}. It is thrown <em>before</em> the cap-exceeding row is written, so
 * the streamed body never contains more than the cap's worth of rows — the
 * export is fail-closed, never a silent truncation of a privacy-sensitive
 * dataset.
 */
public class ExportRowCapExceededException extends RuntimeException {

    private final long maxRows;

    public ExportRowCapExceededException(long maxRows) {
        super("Export exceeds the maximum of " + maxRows + " rows; narrow the scope.");
        this.maxRows = maxRows;
    }

    /** The configured maximum row count that was exceeded. */
    public long maxRows() {
        return maxRows;
    }
}
