package com.example.endpointadmin.remoteaccess;

/**
 * Faz 22.6 C-3 — the durable WORM sink the {@link SessionRecorder} writes each {@link SessionRecordingChain.Entry}
 * to (ADR-0034 D3). A real implementation is an append-only, retention-locked store (DB / object store with a
 * legal-hold lock, no operator delete) — that storage-level WORM impl is a later slice; this interface lets the
 * recorder be written + tested against an in-memory sink first.
 *
 * <p><b>Fail-closed contract:</b> {@link #append} MUST throw if the entry cannot be DURABLY committed — the
 * recorder treats a failed write as a recording loss (the session may not continue unrecorded). A sink must
 * never silently drop a write.
 */
public interface RecordingSink {

    /**
     * Durably append the entry (WORM — append-only, never overwrite/delete).
     *
     * @throws RecordingSinkException if the entry could not be durably committed
     */
    void append(SessionRecordingChain.Entry entry) throws RecordingSinkException;

    /** Whether the sink can currently accept writes (a liveness probe for the recorder health). */
    boolean isWritable();

    /** Thrown when a recording entry cannot be durably committed (fail-closed signal to the recorder). */
    class RecordingSinkException extends Exception {
        public RecordingSinkException(String message) {
            super(message);
        }

        public RecordingSinkException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
