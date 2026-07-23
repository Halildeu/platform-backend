package com.example.ethics.repository;

import com.example.ethics.model.RevealRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevealRequestRepository extends JpaRepository<RevealRequest, UUID> {
    List<RevealRequest> findAllByCaseIdOrderByRequestedAtDesc(UUID caseId);
    List<RevealRequest> findAllByStatusOrderByRequestedAtDesc(String status);
}
