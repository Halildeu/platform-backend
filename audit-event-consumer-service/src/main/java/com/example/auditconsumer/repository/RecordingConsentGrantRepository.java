package com.example.auditconsumer.repository;

import com.example.auditconsumer.model.RecordingConsentGrant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordingConsentGrantRepository extends JpaRepository<RecordingConsentGrant, UUID> {
    Optional<RecordingConsentGrant> findByEventKey(String eventKey);
    Optional<RecordingConsentGrant> findByCaptureId(UUID captureId);
}
