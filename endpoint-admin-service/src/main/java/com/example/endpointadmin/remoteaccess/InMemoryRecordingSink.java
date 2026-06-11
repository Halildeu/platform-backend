package com.example.endpointadmin.remoteaccess;

import java.util.ArrayList;
import java.util.List;

/**
 * Faz 22.6 C-3 — in-memory reference {@link RecordingSink} for DEV/TEST. Append-only; {@link #setWritable}
 * simulates a sink outage (a write then throws — exercising the recorder's fail-closed path). The durable
 * append-only retention-locked store is a later slice (this proves the recorder contract without storage).
 */
public final class InMemoryRecordingSink implements RecordingSink {

    private final List<SessionRecordingChain.Entry> written = new ArrayList<>();
    private volatile boolean writable = true;

    /** Toggle the sink's writability (false → the next {@link #append} fails, modelling a sink outage). */
    public void setWritable(boolean writable) {
        this.writable = writable;
    }

    @Override
    public synchronized void append(SessionRecordingChain.Entry entry) throws RecordingSinkException {
        if (!writable) {
            throw new RecordingSinkException("recording sink is not writable");
        }
        if (entry == null) {
            throw new RecordingSinkException("null recording entry");
        }
        written.add(entry);
    }

    @Override
    public boolean isWritable() {
        return writable;
    }

    /** The durably-written entries (read-many). */
    public synchronized List<SessionRecordingChain.Entry> written() {
        return List.copyOf(written);
    }
}
