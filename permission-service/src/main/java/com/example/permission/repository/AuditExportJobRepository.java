package com.example.permission.repository;

import com.example.permission.model.AuditExportJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditExportJobRepository extends JpaRepository<AuditExportJob, String> {
}
