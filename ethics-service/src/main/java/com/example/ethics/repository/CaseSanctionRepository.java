package com.example.ethics.repository;

import com.example.ethics.model.CaseSanction;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseSanctionRepository extends JpaRepository<CaseSanction, UUID> {

    List<CaseSanction> findAllByCaseIdOrderByDecidedAtDesc(UUID caseId);

    /** Decided but never carried out — the backlog the register exists to make visible. */
    List<CaseSanction> findAllByOrgIdAndAppliedAtIsNull(UUID orgId);
}
