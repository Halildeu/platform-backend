package com.example.audiogateway.controller;

import com.example.audiogateway.config.CorrelationIdWebFilter;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.StartSessionRequest;
import com.example.audiogateway.dto.StartSessionResponse;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Audio Gateway Contract 1.0 — session lifecycle endpoints.
 *
 * <p>3-AI mutabakat Codex {@code 019e879c} + Mavis {@code msg 78} AGREE:
 * tenantId/userId client-trusted DEĞİL — Gateway JWT claim'inden derive eder.
 *
 * <p>Skeleton level: gerçek STT dispatch (Redis producer), WS streaming, chunk admission
 * sonraki PR'larda (PR-queue-01, PR-stt-03). Bu PR yalnız contract freeze + 5 senaryo
 * contract test + JWT validation hook.
 */
@RestController
@RequestMapping("/api/meeting-audio")
public class AudioSessionController {

    @PostMapping("/sessions")
    public Mono<ResponseEntity<StartSessionResponse>> startSession(
            @Valid @RequestBody final StartSessionRequest req,
            @AuthenticationPrincipal final Jwt jwt,
            final ServerWebExchange exchange) {

        // Validate sample rate (Codex 019e879c enum constraint)
        if (!StartSessionRequest.ALLOWED_SAMPLE_RATES.contains(req.sampleRateHz())) {
            final ErrorResponse err = ErrorResponse.of(
                    ErrorResponse.CODE_FORMAT_REJECTED,
                    "Unsupported sample rate: " + req.sampleRateHz(),
                    (String) exchange.getAttributes().get(CorrelationIdWebFilter.ATTR_KEY),
                    false);
            return Mono.just(ResponseEntity.badRequest().<StartSessionResponse>build());
        }

        // Validate channel (PoC: mono only)
        if (req.channels() != 1) {
            return Mono.just(ResponseEntity.badRequest().<StartSessionResponse>build());
        }

        // Validate audio format whitelist (client-allowed subset)
        if (!AudioFormat.CLIENT_ALLOWED.contains(req.audioFormat())) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).<StartSessionResponse>build());
        }

        final String corrId = (String) exchange.getAttributes().get(CorrelationIdWebFilter.ATTR_KEY);
        final String sessionId = "SES-" + UUID.randomUUID();
        final long now = Instant.now().toEpochMilli();

        // Derive tenant/user from JWT claims (Codex 019e879c RED — never from client)
        // For now we just extract them into log/correlation context; downstream wiring in PR-queue-01.
        final String tenantId = jwt != null ? jwt.getClaimAsString("tenantId") : "unknown";
        final String userId = jwt != null ? jwt.getSubject() : "unknown";

        final StartSessionResponse resp = new StartSessionResponse(
                sessionId,
                corrId,
                "/api/meeting-audio/sessions/" + sessionId + "/stream",
                "/api/meeting-audio/sessions/" + sessionId + "/chunks",
                now);
        return Mono.just(ResponseEntity.ok(resp));
    }
}
