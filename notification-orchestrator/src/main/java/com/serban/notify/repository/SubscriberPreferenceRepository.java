package com.serban.notify.repository;

import com.serban.notify.domain.SubscriberPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriberPreferenceRepository extends JpaRepository<SubscriberPreference, Long> {

    List<SubscriberPreference> findBySubscriberIdAndOrgId(String subscriberId, String orgId);

    /**
     * Org-aware preference lookup (Codex 019dfaaa PR5 Q2 absorb).
     *
     * <p>Mevcut V1 schema'da (subscriber_id, topic_key, channel) tek başına
     * unique değildi — aynı subscriber_id farklı org'larda aynı topic/channel
     * için farklı tercih kaydedebilirdi. PR5 V5 migration org-aware unique
     * index ekliyor; bu metot ona uyumlu.
     */
    Optional<SubscriberPreference> findByOrgIdAndSubscriberIdAndTopicKeyAndChannel(
        String orgId, String subscriberId, String topicKey, String channel
    );

    /** Wildcard channel preference (channel=NULL → all channels). */
    Optional<SubscriberPreference> findByOrgIdAndSubscriberIdAndTopicKeyAndChannelIsNull(
        String orgId, String subscriberId, String topicKey
    );

    /** Wildcard topic preference (topic_key=NULL → all topics). */
    Optional<SubscriberPreference> findByOrgIdAndSubscriberIdAndTopicKeyIsNullAndChannel(
        String orgId, String subscriberId, String channel
    );

    /**
     * Both-null wildcard preference (topic_key IS NULL AND channel IS
     * NULL → "all topics, all channels"). Faz 23.5 PR2 absorb: the
     * subscriber-facing upsert API can write this row, so the dispatch-
     * time evaluation must also read it; otherwise a UI "mute all" rule
     * has no effect on delivery (Codex iter P1).
     */
    Optional<SubscriberPreference> findByOrgIdAndSubscriberIdAndTopicKeyIsNullAndChannelIsNull(
        String orgId, String subscriberId
    );
}
