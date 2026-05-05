package com.serban.notify.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Subscriber preference — per-channel + per-topic + critical bypass (ADR-0013 D46 #8).
 */
@Entity
@Table(name = "subscriber_preference", schema = "notify")
public class SubscriberPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subscriber_id", nullable = false, length = 128)
    private String subscriberId;

    @Column(name = "org_id", nullable = false, length = 64)
    private String orgId;

    @Column(name = "topic_key", length = 128)
    private String topicKey;

    @Column(length = 32)
    private String channel;

    @Column(nullable = false)
    private boolean enabled = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quiet_hours", columnDefinition = "jsonb")
    private Map<String, Object> quietHours;

    @Column(name = "frequency_limit_per_day")
    private Integer frequencyLimitPerDay;

    @Column(name = "bypass_for_critical", nullable = false)
    private boolean bypassForCritical = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSubscriberId() { return subscriberId; }
    public void setSubscriberId(String subscriberId) { this.subscriberId = subscriberId; }
    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
    public String getTopicKey() { return topicKey; }
    public void setTopicKey(String topicKey) { this.topicKey = topicKey; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, Object> getQuietHours() { return quietHours; }
    public void setQuietHours(Map<String, Object> quietHours) { this.quietHours = quietHours; }
    public Integer getFrequencyLimitPerDay() { return frequencyLimitPerDay; }
    public void setFrequencyLimitPerDay(Integer frequencyLimitPerDay) {
        this.frequencyLimitPerDay = frequencyLimitPerDay;
    }
    public boolean isBypassForCritical() { return bypassForCritical; }
    public void setBypassForCritical(boolean bypassForCritical) { this.bypassForCritical = bypassForCritical; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SubscriberPreference that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
