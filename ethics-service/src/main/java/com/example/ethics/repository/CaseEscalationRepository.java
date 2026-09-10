package com.example.ethics.repository;

import com.example.ethics.model.CaseEscalation;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseEscalationRepository extends JpaRepository<CaseEscalation, UUID> {

    /** Every level reached on a case, in the order they were reached. */
    List<CaseEscalation> findAllByCaseIdOrderByEscalatedAtAscLevelAsc(UUID caseId);

    /** One read for a whole list page instead of one per row. */
    List<CaseEscalation> findAllByCaseIdInOrderByEscalatedAtAscLevelAsc(Collection<UUID> caseIds);

    boolean existsByCaseIdAndObligationAndLevel(UUID caseId, String obligation, int level);
}
