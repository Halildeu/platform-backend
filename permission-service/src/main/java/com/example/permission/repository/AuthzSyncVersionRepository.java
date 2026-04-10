package com.example.permission.repository;

import com.example.permission.model.AuthzSyncVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthzSyncVersionRepository extends JpaRepository<AuthzSyncVersion, Integer> {

    @Modifying
    @Query(value = "UPDATE authz_sync_version SET version = version + 1, updated_at = NOW() WHERE id = 1", nativeQuery = true)
    int incrementVersion();
}
