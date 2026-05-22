package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.AuditChainLock;
import com.example.endpointadmin.audit.AuditChainSupport;
import com.example.endpointadmin.dto.v1.admin.EndpointAuditEventDto;
import com.example.endpointadmin.model.EndpointAuditEvent;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EndpointAuditService {

    private final EndpointAuditEventRepository repository;
    private final AuditChainLock auditChainLock;
    private final Clock clock;

    public EndpointAuditService(EndpointAuditEventRepository repository,
                                AuditChainLock auditChainLock,
                                Clock clock) {
        this.repository = repository;
        this.auditChainLock = auditChainLock;
        this.clock = clock;
    }

    /**
     * Record an audit event with a tenant-scoped tamper-evident hash-chain
     * (BE-016, Codex 019e4f8e).
     *
     * <p>Within the caller transaction this:
     * <ol>
     *   <li>assigns the row id + a microsecond-normalized {@code occurredAt}
     *       up-front (both feed the canonical payload, so they must be known
     *       before hashing);</li>
     *   <li>acquires the per-tenant {@link AuditChainLock} so concurrent
     *       writers for the same tenant cannot fork the chain;</li>
     *   <li>reads the current chain tail (most recent hashed row for the
     *       tenant; legacy pre-BE-016 null-hash rows are skipped, so the first
     *       post-deploy row is the tenant GENESIS);</li>
     *   <li>computes {@code event_hash = SHA-256(domain || prev || canonical)}
     *       and persists the four hash columns.</li>
     * </ol>
     *
     * <p>MUST run inside an active transaction (the advisory lock is
     * transaction-scoped). All current callers are {@code @Transactional}.
     */
    public EndpointAuditEvent record(UUID tenantId,
                                     EndpointDevice device,
                                     EndpointCommand command,
                                     String eventType,
                                     String action,
                                     String performedBySubject,
                                     String correlationId,
                                     Map<String, Object> metadata,
                                     Map<String, Object> beforeState,
                                     Map<String, Object> afterState) {
        EndpointAuditEvent event = new EndpointAuditEvent();
        // id + occurredAt assigned up-front: both are part of the canonical
        // hash payload, so they must be deterministic before the hash is
        // computed (the @PrePersist occurredAt fallback stays as a safety net).
        event.setId(UUID.randomUUID());
        event.setOccurredAt(AuditChainSupport.normalizeTimestamp(clock.instant()));
        event.setTenantId(tenantId);
        event.setDevice(device);
        event.setCommand(command);
        event.setEventType(eventType);
        event.setAction(action);
        event.setPerformedBySubject(performedBySubject);
        event.setCorrelationId(correlationId);
        event.setMetadata(metadata);
        event.setBeforeState(beforeState);
        event.setAfterState(afterState);

        // BE-016: serialize the tenant chain, link to the prior hash, hash.
        auditChainLock.lockTenantChain(tenantId);
        String prevHash = repository
                .findTop1ByTenantIdAndEventHashIsNotNullOrderByOccurredAtDescIdDesc(tenantId)
                .map(EndpointAuditEvent::getEventHash)
                .orElse(null);
        event.setEventHashAlg(AuditChainSupport.HASH_ALGORITHM);
        event.setEventHashVersion(AuditChainSupport.HASH_VERSION);
        event.setPrevEventHash(prevHash);
        event.setEventHash(AuditChainSupport.computeEventHash(prevHash, event));

        return repository.save(event);
    }


    public List<EndpointAuditEventDto> listEvents(UUID tenantId,
                                                  UUID deviceId,
                                                  UUID commandId,
                                                  String eventType,
                                                  int limit) {
        int resolvedLimit = Math.max(1, Math.min(limit, 200));
        String resolvedEventType = trimToNull(eventType);
        return repository.search(tenantId, deviceId, commandId, resolvedEventType, PageRequest.of(0, resolvedLimit))
                .stream()
                .map(this::toDto)
                .toList();
    }

    private EndpointAuditEventDto toDto(EndpointAuditEvent event) {
        UUID deviceId = event.getDevice() == null ? null : event.getDevice().getId();
        UUID commandId = event.getCommand() == null ? null : event.getCommand().getId();
        return new EndpointAuditEventDto(
                event.getId(),
                event.getTenantId(),
                deviceId,
                commandId,
                event.getEventType(),
                event.getAction(),
                event.getPerformedBySubject(),
                event.getCorrelationId(),
                event.getMetadata(),
                event.getBeforeState(),
                event.getAfterState(),
                event.getOccurredAt()
        );
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
