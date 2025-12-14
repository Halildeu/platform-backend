package com.example.permission.repository;

import com.example.permission.model.Scope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScopeRepository extends JpaRepository<Scope, Long> {
    Optional<Scope> findByScopeTypeIgnoreCaseAndRefId(String scopeType, Long refId);
}
