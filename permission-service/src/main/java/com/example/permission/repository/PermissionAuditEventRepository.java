package com.example.permission.repository;

import com.example.permission.model.PermissionAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PermissionAuditEventRepository extends JpaRepository<PermissionAuditEvent, Long>, JpaSpecificationExecutor<PermissionAuditEvent> {
}
