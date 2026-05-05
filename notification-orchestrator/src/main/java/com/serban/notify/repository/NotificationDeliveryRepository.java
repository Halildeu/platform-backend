package com.serban.notify.repository;

import com.serban.notify.domain.NotificationDelivery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    List<NotificationDelivery> findByIntentId(String intentId);

    @Query("SELECT d FROM NotificationDelivery d WHERE d.status = :status " +
           "AND d.nextRetryAt <= :now")
    List<NotificationDelivery> findDueForRetry(
        @Param("status") NotificationDelivery.Status status,
        @Param("now") OffsetDateTime now,
        Pageable pageable
    );

    long countByStatus(NotificationDelivery.Status status);
}
