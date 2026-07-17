package com.example.auditconsumer.repository;

import com.example.auditconsumer.model.RecordingConsentRevocation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordingConsentRevocationRepository
        extends JpaRepository<RecordingConsentRevocation, UUID> {

    Optional<RecordingConsentRevocation> findByEventKey(String eventKey);
}
