package com.example.user.repository;

import com.example.user.model.UserNotificationPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserNotificationPreferenceRepository extends JpaRepository<UserNotificationPreference, Long> {

    List<UserNotificationPreference> findByUserId(Long userId);

    Optional<UserNotificationPreference> findByUserIdAndChannel(Long userId, String channel);
}

