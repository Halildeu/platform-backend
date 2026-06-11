package com.example.endpointadmin.remoteaccess;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Faz 22.6 C-1 — the tamper-evident WORM recording chain (ADR-0033 §6, ADR-0034 D3: mandatory fail-closed
 * recording, WORM + hash-chain). Each appended {@link Entry} links to the previous one by a SHA-256 hash, so
 * the recording is append-only and any modification / insertion / deletion / re-ordering breaks the chain and
 * is detected by {@link #verifyIntegrity()}. Pure + offline — no storage; the durable append-only sink (with
 * the 7y-metadata / 90d-raw retention lock) + the out-of-band signed anchor are the C-2/C-3 slices, and the
 * heartbeat {@code recordingWriterAck} wiring (a session may not stay ACTIVE if the recorder is unhealthy)
 * is C-3.
 *
 * <p>This records METADATA (the content hash, not the raw content) — the raw stream is written separately
 * under its own (shorter) retention; the chain proves the metadata trail wasn't altered after the fact.
 *
 * <p><b>WORM:</b> {@link #append} is the only mutator; there is no update/delete. The genesis entry links to
 * a fixed {@link #GENESIS_HASH}. {@code timestampMillis} is supplied by the caller (never a hidden clock).
 */
public final class SessionRecordingChain {

    /** The kinds of metadata event recorded in the session trail. */
    public enum RecordKind {
        SESSION_START, OPERATOR_COMMAND, AGENT_OUTPUT, POLICY_EVENT, KILL, SESSION_END
    }

    /** The fixed link for the first entry (so even the genesis entry is hash-anchored). */
    public static final String GENESIS_HASH = "0".repeat(64);

    /**
     * One WORM record. {@code contentHash} is the SHA-256 hex of the actual content (metadata recording);
     * {@code entryHash} chains this entry to {@code previousHash}.
     */
    public record Entry(long seq, long timestampMillis, RecordKind kind, String contentHash,
                        String previousHash, String entryHash) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private String headHash = GENESIS_HASH;

    /**
     * Append a metadata record, chaining it to the current head. Returns the new entry.
     *
     * @param kind            the event kind (non-null)
     * @param contentHash     the SHA-256 hex of the content (non-blank — recording is content-bound)
     * @param timestampMillis the caller's event time (monotonic, non-negative)
     */
    public synchronized Entry append(RecordKind kind, String contentHash, long timestampMillis) {
        if (kind == null || contentHash == null || contentHash.isBlank() || timestampMillis < 0) {
            throw new IllegalArgumentException("kind + contentHash must be set and timestampMillis non-negative");
        }
        long seq = entries.size();
        String entryHash = hashEntry(seq, timestampMillis, kind, contentHash, headHash);
        Entry entry = new Entry(seq, timestampMillis, kind, contentHash, headHash, entryHash);
        entries.add(entry);
        headHash = entryHash;
        return entry;
    }

    /** The current chain head — the value an out-of-band signed audit anchor commits to (C-2). */
    public synchronized String headHash() {
        return headHash;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** An immutable snapshot of the recorded entries (read-many; the chain itself is write-once per entry). */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /**
     * Recompute the whole chain and confirm every entry's hash + prev-linkage is intact — i.e. no entry was
     * altered, inserted, removed, or re-ordered after the fact. Returns {@code true} iff the chain is sound
     * AND its last hash matches the live head.
     */
    public synchronized boolean verifyIntegrity() {
        if (!verifyChain(entries)) {
            return false;
        }
        return entries.isEmpty()
                ? GENESIS_HASH.equals(headHash)
                : entries.get(entries.size() - 1).entryHash().equals(headHash);
    }

    /**
     * Verify an entry list stands alone as a sound hash-chain: contiguous {@code seq} from 0, each
     * {@code previousHash} links to the prior {@code entryHash} (genesis → {@link #GENESIS_HASH}), and each
     * {@code entryHash} re-computes from its fields. Detects alteration / insertion / removal / re-ordering.
     * Static so an auditor (or the C-2 out-of-band sink) can verify a persisted chain independently.
     */
    public static boolean verifyChain(List<Entry> chain) {
        String expectedPrev = GENESIS_HASH;
        for (int i = 0; i < chain.size(); i++) {
            Entry e = chain.get(i);
            if (e == null || e.seq() != i || e.kind() == null || e.contentHash() == null
                    || !expectedPrev.equals(e.previousHash())) {
                return false; // re-ordered / inserted / removed / malformed
            }
            String recomputed = hashEntry(e.seq(), e.timestampMillis(), e.kind(), e.contentHash(), e.previousHash());
            if (!recomputed.equals(e.entryHash())) {
                return false; // a field was altered after the entry was sealed
            }
            expectedPrev = e.entryHash();
        }
        return true;
    }

    /** Domain-separation tag in the hash preimage (Codex 019eb7d6): this chain's entry hashes can never
     *  collide with another length-prefixed hash (e.g. the attestation canonical) or a future chain format. */
    private static final String HASH_DOMAIN = "SessionRecordingChain:v1";

    /** SHA-256 over the length-prefixed canonical entry (delimiter-safe — no field can alias the framing). */
    private static String hashEntry(long seq, long timestampMillis, RecordKind kind, String contentHash,
                                    String previousHash) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeField(out, HASH_DOMAIN); // domain separation FIRST
        writeField(out, Long.toString(seq));
        writeField(out, Long.toString(timestampMillis));
        writeField(out, kind.name());
        writeField(out, contentHash);
        writeField(out, previousHash);
        return CertThumbprint.ofDer(out.toByteArray());
    }

    private static void writeField(ByteArrayOutputStream out, String field) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        out.write((bytes.length >>> 24) & 0xFF);
        out.write((bytes.length >>> 16) & 0xFF);
        out.write((bytes.length >>> 8) & 0xFF);
        out.write(bytes.length & 0xFF);
        out.writeBytes(bytes);
    }
}
