package com.example.ethics.repository;

import com.example.ethics.model.OrgReportPolicy;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgReportPolicyRepository extends JpaRepository<OrgReportPolicy, UUID> {
}
