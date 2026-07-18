package com.example.transcript.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.transcript.dto.CanonicalTranscriptSnapshotDto;
import com.example.transcript.security.AnalysisJobCapabilityIssuer;
import com.example.transcript.service.CanonicalTranscriptReadService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class CanonicalTranscriptInternalControllerTest {

    @Mock
    private CanonicalTranscriptReadService service;

    @InjectMocks
    private CanonicalTranscriptInternalController controller;

    @Test
    void read_returnsSnapshotWithoutCapabilityHeaders() {
        UUID tenantId = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        CanonicalTranscriptSnapshotDto snapshot = snapshot(tenantId, meetingId, sessionId);
        when(service.read(tenantId, meetingId, sessionId, 4L, tenantId, "meeting-ai"))
                .thenReturn(snapshot);

        var response = controller.read(
                tenantId, meetingId, sessionId, 4L, tenantId,
                UsernamePasswordAuthenticationToken.authenticated(
                        "meeting-ai", "n/a", List.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(snapshot);
        assertThat(response.getHeaders().containsKey(
                CanonicalTranscriptInternalController.CAPABILITY_HEADER)).isFalse();
        assertThat(response.getHeaders().containsKey(
                CanonicalTranscriptInternalController.CAPABILITY_EXPIRES_HEADER)).isFalse();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        verify(service).read(tenantId, meetingId, sessionId, 4L, tenantId, "meeting-ai");
    }

    @Test
    void issueCapability_returnsExactly204WithHeaderOnlyResult() {
        UUID tenantId = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-07-18T03:05:00Z");
        when(service.issueAnalysisCapability(
                tenantId, meetingId, sessionId, 4L, tenantId, runId,
                "meeting-intelligence-v1"))
                .thenReturn(new AnalysisJobCapabilityIssuer.IssuedCapability(
                        "signed-capability", UUID.randomUUID(), expiresAt));

        var response = controller.issueAnalysisCapability(
                tenantId, meetingId, sessionId, 4L, tenantId, runId,
                "meeting-intelligence-v1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getFirst(
                CanonicalTranscriptInternalController.CAPABILITY_HEADER))
                .isEqualTo("signed-capability");
        assertThat(response.getHeaders().getFirst(
                CanonicalTranscriptInternalController.CAPABILITY_EXPIRES_HEADER))
                .isEqualTo(expiresAt.toString());
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(service).issueAnalysisCapability(
                tenantId, meetingId, sessionId, 4L, tenantId, runId,
                "meeting-intelligence-v1");
    }

    private static CanonicalTranscriptSnapshotDto snapshot(
            UUID tenantId, UUID meetingId, UUID sessionId) {
        return new CanonicalTranscriptSnapshotDto(
                tenantId, meetingId, sessionId, 4L,
                Instant.parse("2026-07-18T03:00:00Z"), "FINALIZED",
                "canonical text", "a".repeat(64), 1, List.of());
    }
}
