package com.serban.notify.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Dead letter — DLQ + manual replay (ADR-0013 D46 #4).
 */
@Entity
@Table(name = "dead_letter", schema = "notify")
public class DeadLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "intent_id", nullable = false, length = 64)
    private String intentId;

    @Column(name = "delivery_id", nullable = false)
    private Long deliveryId;

    @Column(nullable = false, length = 32)
    private String channel;

    @Column(name = "recipient_hash", nullable = false, length = 64)
    private String recipientHash;

    @Column(length = 64)
    private String provider;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_failure_reason", columnDefinition = "text")
    private String lastFailureReason;

    @Column(name = "last_failure_at", nullable = false)
    private OffsetDateTime lastFailureAt;

    @Column(name = "moved_to_dlq_at", nullable = false)
    private OffsetDateTime movedToDlqAt;

    @Column(nullable = false)
    private boolean replayed = false;

    @Column(name = "replayed_at")
    private OffsetDateTime replayedAt;

    @Column(name = "replayed_by", length = 128)
    private String replayedBy;

    @PrePersist
    void prePersist() {
        if (movedToDlqAt == null) movedToDlqAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getIntentId() { return intentId; }
    public void setIntentId(String intentId) { this.intentId = intentId; }
    public Long getDeliveryId() { return deliveryId; }
    public void setDeliveryId(Long deliveryId) { this.deliveryId = deliveryId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getRecipientHash() { return recipientHash; }
    public void setRecipientHash(String recipientHash) { this.recipientHash = recipientHash; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public String getLastFailureReason() { return lastFailureReason; }
    public void setLastFailureReason(String lastFailureReason) { this.lastFailureReason = lastFailureReason; }
    public OffsetDateTime getLastFailureAt() { return lastFailureAt; }
    public void setLastFailureAt(OffsetDateTime lastFailureAt) { this.lastFailureAt = lastFailureAt; }
    public OffsetDateTime getMovedToDlqAt() { return movedToDlqAt; }
    public boolean isReplayed() { return replayed; }
    public void setReplayed(boolean replayed) { this.replayed = replayed; }
    public OffsetDateTime getReplayedAt() { return replayedAt; }
    public void setReplayedAt(OffsetDateTime replayedAt) { this.replayedAt = replayedAt; }
    public String getReplayedBy() { return replayedBy; }
    public void setReplayedBy(String replayedBy) { this.replayedBy = replayedBy; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeadLetter that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() { return getClass().hashCode();
    }
}
