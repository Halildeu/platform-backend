package com.serban.notify.repository;

import com.serban.notify.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    @Query("SELECT k FROM IdempotencyKey k WHERE k.orgId = :orgId " +
           "AND k.idempotencyKey = :key AND k.expiresAt > :now")
    Optional<IdempotencyKey> findActiveKey(
        @Param("orgId") String orgId,
        @Param("key") String key,
        @Param("now") OffsetDateTime now
    );

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :cutoff")
    int purgeExpired(@Param("cutoff") OffsetDateTime cutoff);
}
