package com.serban.notify.repository;

import com.serban.notify.domain.SubscriberPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriberPreferenceRepository extends JpaRepository<SubscriberPreference, Long> {

    List<SubscriberPreference> findBySubscriberIdAndOrgId(String subscriberId, String orgId);

    Optional<SubscriberPreference> findBySubscriberIdAndTopicKeyAndChannel(
        String subscriberId, String topicKey, String channel
    );
}
