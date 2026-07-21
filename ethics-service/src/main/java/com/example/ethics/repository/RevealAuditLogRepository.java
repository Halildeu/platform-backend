package com.example.ethics.repository;

import com.example.ethics.model.RevealAuditLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Faz 35 ES-303 WORM repository — deliberately narrow surface. Only
 * append ({@code save}) and read are exposed. There is no {@code delete*},
 * {@code deleteById}, or {@code save} taking an already-persisted id
 * with modified fields is prevented at the application layer by never
 * mutating a loaded entity in the reveal workflow.
 */
public interface RevealAuditLogRepository extends Repository<RevealAuditLog, UUID> {
    RevealAuditLog save(RevealAuditLog log);
    List<RevealAuditLog> findAllByRequestIdOrderByCreatedAtAsc(UUID requestId);
    List<RevealAuditLog> findAllByCaseIdOrderByCreatedAtAsc(UUID caseId);
}
