package com.example.ethics.repository;

import com.example.ethics.model.EvidenceDerivation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceDerivationRepository extends JpaRepository<EvidenceDerivation, Long> {
    Optional<EvidenceDerivation> findFirstByAttachmentIdOrderByDerivationVersionDesc(UUID attachmentId);
    long countByAttachmentId(UUID attachmentId);
}
