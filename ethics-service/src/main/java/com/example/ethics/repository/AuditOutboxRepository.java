package com.example.ethics.repository;
import com.example.ethics.model.AuditOutbox; import java.util.UUID; import org.springframework.data.jpa.repository.JpaRepository;
public interface AuditOutboxRepository extends JpaRepository<AuditOutbox,UUID>{}
