package com.example.ethics.repository;
import com.example.ethics.model.CaseParticipant; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface CaseParticipantRepository extends JpaRepository<CaseParticipant,UUID>{
    List<CaseParticipant> findAllByCaseIdOrderByCreatedAtAsc(UUID caseId);
    Optional<CaseParticipant> findByCaseIdAndKcSubjectAndRole(UUID caseId,String kcSubject,String role);
    // Tenant-scoped on purpose: a lookup that omitted org_id could answer across
    // orgs, and this repository feeds routing and conflict decisions.
    List<CaseParticipant> findAllByOrgIdAndKcSubject(UUID orgId,String kcSubject);

    /**
     * How many people are on each of these cases, in one query.
     *
     * <p>The list needs this twice over: to show whether a case is unattended, and to
     * decide whether the retired assignment label is still worth showing. Asking per case
     * turned a 138-case list into 138 round trips.
     */
    @org.springframework.data.jpa.repository.Query(
            "select p.caseId, count(p) from CaseParticipant p where p.caseId in :caseIds group by p.caseId")
    List<Object[]> countByCaseIdIn(@org.springframework.data.repository.query.Param("caseIds") Collection<UUID> caseIds);
}
