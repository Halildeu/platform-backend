package com.serban.notify.repository;

import com.serban.notify.domain.DeadLetter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {

    @Query("SELECT d FROM DeadLetter d WHERE d.replayed = false ORDER BY d.movedToDlqAt DESC")
    List<DeadLetter> findUnreplayed(Pageable pageable);

    long countByReplayedFalse();
}
