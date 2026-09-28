package com.example.audiogateway.controller;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.FinishResponse;
import com.example.audiogateway.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AudioSessionAbandonControllerTest {
    final AudioSessionRegistry registry = mock(AudioSessionRegistry.class);
    final AudioChunkDispatcher dispatcher = mock(AudioChunkDispatcher.class);
    final AudioSessionController controller = new AudioSessionController(new AudioGatewayProperties(), registry, dispatcher,
            mock(AudioGatewayAuditSink.class), mock(MeetingAccessValidator.class), new SimpleMeterRegistry(),
            mock(DirectSttTranscriptEventReader.class), mock(AudioSessionExpiryCoordinator.class));
    final MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/audio-gateway/sessions/SES-owned/abandon"));
    final Jwt owner = Jwt.withTokenValue("fixture").header("alg", "none").claim("companyId", 1).claim("userId", 2).build();
    final String key = "SES-owned:mobile-abandon";
    @org.junit.jupiter.api.BeforeEach void correlationFilterFixture() {
        exchange.getAttributes().put(com.example.audiogateway.config.CorrelationIdWebFilter.ATTR_KEY, "fixture-correlation");
    }

    @Test void rejectsMissingAuthAndIdempotencyBeforeMutation() {
        assertThat(controller.abandon("SES-owned", key, null, exchange).block().getStatusCode().value()).isEqualTo(401);
        assertThat(controller.abandon("SES-owned", null, owner, exchange).block().getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(registry, dispatcher);
    }
    @Test void returnsExplicitAbsentReceiptAndRetryableCleanupFailure() {
        when(registry.abandon(eq("SES-owned"), eq(key), eq(1L), eq(2L), anyLong(), anyString(), eq(dispatcher)))
                .thenReturn(new AudioSessionRegistry.AbandonOutcome.NotFound(), new AudioSessionRegistry.AbandonOutcome.CleanupFailed());
        var missing = controller.abandon("SES-owned", key, owner, exchange).block();
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(((ErrorResponse) missing.getBody()).code()).isEqualTo("AUDIO_GATEWAY_SESSION_NOT_FOUND");
        var retry = controller.abandon("SES-owned", key, owner, exchange).block();
        assertThat(retry.getStatusCode().value()).isEqualTo(503);
        assertThat(((ErrorResponse) retry.getBody()).retryable()).isTrue();
        verify(dispatcher, never()).finishSession(any());
    }
    @Test void receiptUsesAbandonedStateAndPreservesExactRetryTimestamp() {
        SessionRecord record = mock(SessionRecord.class);
        when(record.finishedAtMs()).thenReturn(2000L);
        when(registry.abandon(eq("SES-owned"), eq(key), eq(1L), eq(2L), anyLong(), anyString(), eq(dispatcher)))
                .thenReturn(new AudioSessionRegistry.AbandonOutcome.Abandoned(record, true));
        var result = (FinishResponse) controller.abandon("SES-owned", key, owner, exchange).block().getBody();
        assertThat(result.finalState()).isEqualTo("ABANDONED"); assertThat(result.finishedAtMs()).isEqualTo(2000L);
        assertThat(result.alreadyFinished()).isTrue();
        verify(dispatcher, never()).finishSession(any());
    }
}
