package com.example.ethics.repository;

import com.example.ethics.model.CaseWaitingReason;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseWaitingReasonRepository extends JpaRepository<CaseWaitingReason, UUID> {

    /** The wait still open on this case, if any. At most one exists — see V14. */
    Optional<CaseWaitingReason> findByCaseIdAndEndedAtIsNull(UUID caseId);
}
