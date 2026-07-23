package com.example.ethics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Product-local, append-only audit ledger row.
 *
 * <p>The application has no update/delete repository method and PostgreSQL
 * rejects both operations with a trigger. The hash chain makes historical
 * replacement, insertion and reordering detectable.
 */
@Entity
@Immutable
@Table(name = "ethics_worm_audit")
public class WormAuditEntry {
    @Id
    @Column(name = "seq")
    private Long seq;
    @Column(nullable = false)
    private UUID id;
    @Column(name = "source_outbox_id", nullable = false)
    private UUID sourceOutboxId;
    @Column(name = "org_id", nullable = false)
    private UUID orgId;
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;
    @Column(name = "event_type", nullable = false)
    private String eventType;
    @Column(nullable = false, length = 4000)
    private String payload;
    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;
    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;
    @Column(name = "prev_hash")
    private String prevHash;
    @Column(name = "entry_hash", nullable = false)
    private String entryHash;
    @Column(name = "entry_hash_alg", nullable = false)
    private String entryHashAlg;
    @Column(name = "entry_hash_version", nullable = false)
    private int entryHashVersion;

    protected WormAuditEntry() {
    }

    public Long getSeq() { return seq; }
    public UUID getId() { return id; }
    public UUID getSourceOutboxId() { return sourceOutboxId; }
    public UUID getOrgId() { return orgId; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getEventTimestamp() { return eventTimestamp; }
    public Instant getIngestedAt() { return ingestedAt; }
    public String getPrevHash() { return prevHash; }
    public String getEntryHash() { return entryHash; }
    public String getEntryHashAlg() { return entryHashAlg; }
    public int getEntryHashVersion() { return entryHashVersion; }
}
