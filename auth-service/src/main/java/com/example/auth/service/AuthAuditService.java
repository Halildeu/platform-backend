package com.example.auth.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.example.auth.model.AuthAuditEvent;
import com.example.auth.permission.PermissionAuditMirrorClient;
import com.example.auth.repository.AuthAuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class AuthAuditService {

    private static final String SERVICE_NAME = "auth-service";
    private static final String TRACE_ID_KEY = "traceId";

    private final AuthAuditEventRepository authAuditEventRepository;
    private final PermissionAuditMirrorClient permissionAuditMirrorClient;
    private final ObjectMapper objectMapper;

    public AuthAuditService(AuthAuditEventRepository authAuditEventRepository,
                            PermissionAuditMirrorClient permissionAuditMirrorClient,
                            ObjectMapper objectMapper) {
        this.authAuditEventRepository = authAuditEventRepository;
        this.permissionAuditMirrorClient = permissionAuditMirrorClient;
        this.objectMapper = objectMapper;
    }

    public AuthAuditEvent recordSessionCreated(Long userId,
                                               String userEmail,
                                               Long companyId,
                                               String role,
                                               Set<String> permissions,
                                               int sessionTimeoutMinutes,
                                               long expiresAtEpochMillis) {
        Map<String, Object> metadata = buildSessionMetadata(companyId, role, permissions, sessionTimeoutMinutes, expiresAtEpochMillis);
        AuthAuditEvent event = new AuthAuditEvent();
        event.setEventType("SESSION_CREATED");
        event.setPerformedBy(userId);
        event.setUserEmail(userEmail);
        event.setService(SERVICE_NAME);
        event.setLevel("INFO");
        event.setAction("SESSION_CREATED");
        event.setCorrelationId(resolveCorrelationId());
        event.setDetails(buildSessionCreatedDetails(userEmail, companyId));
        event.setMetadata(writeJsonSafe(metadata));
        event.setOccurredAt(Instant.now());
        AuthAuditEvent saved = authAuditEventRepository.save(event);
        permissionAuditMirrorClient.mirror(saved, metadata, Map.of(), Map.of());
        return saved;
    }

    private String buildSessionCreatedDetails(String userEmail, Long companyId) {
        if (companyId == null) {
            return "Session created for %s in global scope".formatted(userEmail);
        }
        return "Session created for %s in company scope %d".formatted(userEmail, companyId);
    }

    private Map<String, Object> buildSessionMetadata(Long companyId,
                                                     String role,
                                                     Set<String> permissions,
                                                     int sessionTimeoutMinutes,
                                                     long expiresAtEpochMillis) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capabilityId", "platform.identity.session-bootstrap");
        metadata.put("companyId", companyId);
        metadata.put("role", role);
        metadata.put("permissions", permissions == null ? Set.of() : permissions);
        metadata.put("permissionCount", permissions == null ? 0 : permissions.size());
        metadata.put("sessionTimeoutMinutes", sessionTimeoutMinutes);
        metadata.put("expiresAt", Instant.ofEpochMilli(expiresAtEpochMillis).toString());
        return metadata;
    }

    private String resolveCorrelationId() {
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        return UUID.randomUUID().toString();
    }

    private String writeJsonSafe(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return value.toString();
        }
    }
}
