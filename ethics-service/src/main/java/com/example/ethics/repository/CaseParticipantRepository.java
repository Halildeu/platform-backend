package com.example.ethics.repository;
import com.example.ethics.model.CaseParticipant; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface CaseParticipantRepository extends JpaRepository<CaseParticipant,UUID>{
    List<CaseParticipant> findAllByCaseIdOrderByCreatedAtAsc(UUID caseId);
    Optional<CaseParticipant> findByCaseIdAndKcSubjectAndRole(UUID caseId,String kcSubject,String role);
    // Tenant-scoped on purpose: a lookup that omitted org_id could answer across
    // orgs, and this repository feeds routing and conflict decisions.
    List<CaseParticipant> findAllByOrgIdAndKcSubject(UUID orgId,String kcSubject);
}
