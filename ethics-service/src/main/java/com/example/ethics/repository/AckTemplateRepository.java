package com.example.ethics.repository;

import com.example.ethics.model.AckTemplate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AckTemplateRepository extends JpaRepository<AckTemplate, UUID> {

    /**
     * Resolution order is the tenant's voice first, the platform's last:
     * (org, category) → (org, all) → (platform, category) → (platform, all),
     * newest version within each scope. Expressed as one query so the fallback
     * chain cannot drift across call sites: specificity ranks first, then org
     * over platform, then version.
     */
    @Query("""
            select t from AckTemplate t
            where (t.orgId = :orgId or t.orgId is null)
              and (t.category = :category or t.category is null)
            order by
              case when t.orgId is not null and t.category is not null then 0
                   when t.orgId is not null then 1
                   when t.category is not null then 2
                   else 3 end,
              t.version desc
            limit 1
            """)
    Optional<AckTemplate> resolve(@Param("orgId") UUID orgId, @Param("category") String category);
}
