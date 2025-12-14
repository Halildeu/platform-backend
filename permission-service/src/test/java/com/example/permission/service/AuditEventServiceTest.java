package com.example.permission.service;

import com.example.permission.dto.AuditEventPageResponse;
import com.example.permission.dto.AuditEventResponse;
import com.example.permission.model.PermissionAuditEvent;
import com.example.permission.repository.PermissionAuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditEventServiceTest {

    static {
        System.setProperty("net.bytebuddy.experimental", "true");
    }

    @Mock
    private PermissionAuditEventRepository repository;

    @Mock
    private AuditEventStream auditEventStream;

    private AuditEventService auditEventService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        auditEventService = new AuditEventService(repository, objectMapper, auditEventStream, true);
    }

    @Test
    void listEvents_masksSensitiveFields() throws Exception {
        PermissionAuditEvent event = new PermissionAuditEvent();
        event.setId(42L);
        event.setOccurredAt(Instant.parse("2025-01-01T00:00:00Z"));
        event.setUserEmail("alice@example.com");
        event.setService("permission-service");
        event.setLevel("INFO");
        event.setAction("ASSIGN_ROLE");
        event.setCorrelationId("corr-1");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("userId", 10);
        metadata.put("email", "alice@example.com");
        metadata.put("contactName", "Alice Adams");
        metadata.put("nested", Map.of("phoneNumber", "5551234567"));
        event.setMetadata(objectMapper.writeValueAsString(metadata));
        event.setBeforeState(objectMapper.writeValueAsString(Map.of("name", "Bob Builder")));
        event.setAfterState(objectMapper.writeValueAsString(Map.of("addressLine", "Secret Street 123")));

        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(event), PageRequest.of(0, 50), 1));

        AuditEventPageResponse response = auditEventService.listEvents(0, 50, null, Map.of());
        assertThat(response.events()).hasSize(1);

        AuditEventResponse masked = response.events().get(0);
        assertThat(masked.userEmail()).isEqualTo("a***@example.com");
        assertThat(masked.metadata().get("email")).isEqualTo("a***@example.com");
        assertThat(masked.metadata().get("contactName")).isEqualTo("A***");

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) masked.metadata().get("nested");
        assertThat(nested.get("phoneNumber")).isEqualTo("5***");

        assertThat(masked.before().get("name")).isEqualTo("B***");
        assertThat(masked.after().get("addressLine")).isEqualTo("S***");
    }
}
