package com.example.permission.dataaccess;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DataAccessScopeRepository extends JpaRepository<DataAccessScope, Long> {

    List<DataAccessScope> findByUserIdAndRevokedAtIsNull(UUID userId);

    List<DataAccessScope> findByOrgIdAndScopeKindAndScopeRefAndRevokedAtIsNull(
            Long orgId, DataAccessScope.ScopeKind scopeKind, String scopeRef);

    long countByOrgId(Long orgId);
}
