package com.serban.notify.repository;

import com.serban.notify.domain.NotificationIntent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationIntentRepository extends JpaRepository<NotificationIntent, Long> {

    Optional<NotificationIntent> findByIntentId(String intentId);

    @Query("SELECT i FROM NotificationIntent i WHERE i.status = :status " +
           "AND (i.scheduledAt IS NULL OR i.scheduledAt <= :now) " +
           "AND (i.expireAt IS NULL OR i.expireAt > :now)")
    List<NotificationIntent> findDueForProcessing(
        @Param("status") NotificationIntent.Status status,
        @Param("now") OffsetDateTime now,
        Pageable pageable
    );

    long countByStatus(NotificationIntent.Status status);
}
