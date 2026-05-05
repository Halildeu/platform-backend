package com.serban.notify.repository;

import com.serban.notify.domain.DeadLetter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {

    @Query("SELECT d FROM DeadLetter d WHERE d.replayed = false ORDER BY d.movedToDlqAt DESC")
    List<DeadLetter> findUnreplayed(Pageable pageable);

    long countByReplayedFalse();

    /**
     * Native upsert-or-skip insert (Codex 019dfa47 iter-1 P1 absorb).
     *
     * <p>Uses PG {@code ON CONFLICT ... DO NOTHING} on the partial unique index
     * {@code uq_dead_letter_active_delivery (delivery_id) WHERE replayed=FALSE}
     * — concurrent multi-pod insert idempotent without exception path.
     *
     * @return rows affected (0 if duplicate active DLQ already exists, 1 if inserted)
     */
    @Modifying
    @Query(value = """
        INSERT INTO notify.dead_letter
            (intent_id, delivery_id, channel, recipient_hash, provider,
             attempt_count, last_failure_reason, last_failure_at,
             moved_to_dlq_at, replayed)
        VALUES
            (:intentId, :deliveryId, :channel, :recipientHash, :provider,
             :attemptCount, :lastFailureReason, :lastFailureAt,
             :movedToDlqAt, FALSE)
        ON CONFLICT (delivery_id) WHERE replayed = FALSE DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
        @Param("intentId") String intentId,
        @Param("deliveryId") Long deliveryId,
        @Param("channel") String channel,
        @Param("recipientHash") String recipientHash,
        @Param("provider") String provider,
        @Param("attemptCount") int attemptCount,
        @Param("lastFailureReason") String lastFailureReason,
        @Param("lastFailureAt") OffsetDateTime lastFailureAt,
        @Param("movedToDlqAt") OffsetDateTime movedToDlqAt
    );
}
