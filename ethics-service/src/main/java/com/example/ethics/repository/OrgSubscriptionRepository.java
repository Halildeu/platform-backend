package com.example.ethics.repository;

import com.example.ethics.model.OrgSubscription;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgSubscriptionRepository extends JpaRepository<OrgSubscription, UUID> {

    List<OrgSubscription> findAllByOrgIdAndActiveTrue(UUID orgId);
}
