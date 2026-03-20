package com.example.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.auth.model.AuthAuditEvent;
import com.example.auth.permission.PermissionAuditMirrorClient;
import com.example.auth.repository.AuthAuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AuthAuditServiceTest {

    @Mock
    private AuthAuditEventRepository authAuditEventRepository;

    @Mock
    private PermissionAuditMirrorClient permissionAuditMirrorClient;

    private AuthAuditService authAuditService;

    @BeforeEach
    void setUp() {
        authAuditService = new AuthAuditService(
                authAuditEventRepository,
                permissionAuditMirrorClient,
                new ObjectMapper()
        );
    }

    @Test
    void recordSessionCreated_persistsLocalAuditAndMirrorsToCentralFeed() {
        when(authAuditEventRepository.save(any(AuthAuditEvent.class))).thenAnswer(invocation -> {
            AuthAuditEvent event = invocation.getArgument(0);
            event.setId(55L);
            event.setOccurredAt(Instant.parse("2026-03-13T20:00:00Z"));
            return event;
        });

        AuthAuditEvent saved = authAuditService.recordSessionCreated(
                99L,
                "user@example.com",
                42L,
                "USER",
                Set.of("audit-read", "user-read"),
                15,
                Instant.parse("2026-03-13T20:15:00Z").toEpochMilli()
        );

        assertThat(saved.getId()).isEqualTo(55L);
        assertThat(saved.getService()).isEqualTo("auth-service");
        assertThat(saved.getAction()).isEqualTo("SESSION_CREATED");
        assertThat(saved.getMetadata()).contains("platform.identity.session-bootstrap");

        verify(permissionAuditMirrorClient).mirror(
                any(AuthAuditEvent.class),
                any(Map.class),
                any(Map.class),
                any(Map.class)
        );
    }
}
