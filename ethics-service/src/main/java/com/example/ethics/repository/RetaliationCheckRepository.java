package com.example.ethics.repository;

import com.example.ethics.model.RetaliationCheck;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetaliationCheckRepository extends JpaRepository<RetaliationCheck, UUID> {

    List<RetaliationCheck> findAllByCaseIdOrderByPeriodMonthsAsc(UUID caseId);

    /**
     * Due and not yet concluded. The sweeper's read, and the one number that says whether
     * the programme is being run at all.
     */
    List<RetaliationCheck> findAllByOrgIdAndClosedAtIsNullAndDueAtLessThanEqual(UUID orgId, Instant now);

    boolean existsByCaseId(UUID caseId);
}
