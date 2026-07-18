package com.example.transcript.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.transcript.dto.CanonicalTranscriptSnapshotDto;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class CanonicalTranscriptInternalControllerTest {

    @Mock
    private CanonicalTranscriptReadService service;

    @InjectMocks
    private CanonicalTranscriptInternalController controller;

    @Test
    void read_forwardsExactJobTupleAndReturnsNonCacheableCapabilityHeaders() {
        UUID tenantId = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID analysisRunId = UUID.randomUUID();
        Instant finalizedAt = Instant.parse("2026-07-18T03:00:00Z");
        Instant expiresAt = Instant.parse("2026-07-18T03:15:00Z");
        CanonicalTranscriptSnapshotDto snapshot = new CanonicalTranscriptSnapshotDto(
                tenantId,
                meetingId,
                sessionId,
                4L,
                finalizedAt,
                "FINALIZED",
                "canonical text",
                "a".repeat(64),
                1,
                List.of());
        when(service.read(
                        tenantId,
                        meetingId,
                        sessionId,
                        4L,
                        tenantId,
                        analysisRunId,
                        "analysis-v3",
                        "meeting-ai"))
                .thenReturn(new CanonicalTranscriptReadService.CanonicalReadResult(
                        snapshot, "signed-capability", expiresAt));

        var response = controller.read(
                tenantId,
                meetingId,
                sessionId,
                4L,
                tenantId,
                analysisRunId,
                "analysis-v3",
                UsernamePasswordAuthenticationToken.authenticated(
                        "meeting-ai", "n/a", List.of()));

        assertThat(response.getBody()).isSameAs(snapshot);
        assertThat(response.getHeaders().getFirst(CanonicalTranscriptInternalController.CAPABILITY_HEADER))
                .isEqualTo("signed-capability");
        assertThat(response.getHeaders().getFirst(
                        CanonicalTranscriptInternalController.CAPABILITY_EXPIRES_HEADER))
                .isEqualTo(expiresAt.toString());
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        verify(service).read(
                tenantId,
                meetingId,
                sessionId,
                4L,
                tenantId,
                analysisRunId,
                "analysis-v3",
                "meeting-ai");
    }
}
