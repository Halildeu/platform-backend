package com.example.audiogateway.controller;

import com.example.audiogateway.service.LiveAnalysisStreamHub;
import com.example.audiogateway.service.MeetingAccessValidator;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Faz 24 İ5 — live ANALYSIS SSE endpoint (decisions / action items / summary
 * while the meeting is still running).
 *
 * <p>Mirrors {@link LiveTranscriptStreamController} deliberately: same base
 * path, same {@link MeetingAccessValidator} preflight, same heartbeat, same
 * ephemeral no-replay semantics. Only the payload differs — the raw analysis
 * JSON meeting-ai produced, relayed verbatim (see {@link LiveAnalysisStreamHub}
 * for why the gateway relays instead of clients calling meeting-ai directly).
 *
 * <p>Only wired when {@code audio.gateway.direct-stt.live-analyze.enabled=true}
 * (the hub bean exists only then). When disabled the endpoint does not exist.
 */
@RestController
@ConditionalOnBean(LiveAnalysisStreamHub.class)
@RequestMapping("/api/v1/audio-gateway")
public class LiveAnalysisStreamController {

    private static final Logger log = LoggerFactory.getLogger(LiveAnalysisStreamController.class);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final LiveAnalysisStreamHub hub;
    private final MeetingAccessValidator accessValidator;

    @Autowired
    public LiveAnalysisStreamController(
            final LiveAnalysisStreamHub hub, final MeetingAccessValidator accessValidator) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.accessValidator = Objects.requireNonNull(accessValidator, "accessValidator");
    }

    @GetMapping("/meetings/{meetingId}/live-analysis/stream")
    public Mono<ResponseEntity<?>> stream(
            @PathVariable final String meetingId,
            @AuthenticationPrincipal final Jwt jwt,
            final ServerWebExchange exchange) {
        final String corrId = correlationId(exchange);
        if (meetingId == null || meetingId.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        if (jwt == null) {
            return Mono.just(ResponseEntity.status(401).build());
        }
        return accessValidator
                .validate(meetingId, jwt, corrId)
                .map(
                        decision -> {
                            if (!decision.allowed()) {
                                log.debug(
                                        "live-analysis SSE denied meetingId={} status={} correlationId={}",
                                        meetingId,
                                        decision.status(),
                                        corrId);
                                return ResponseEntity.status(decision.status()).build();
                            }
                            final Flux<ServerSentEvent<String>> events =
                                    hub.subscribe(meetingId)
                                            .map(
                                                    payload ->
                                                            ServerSentEvent.<String>builder(payload)
                                                                    .event("analysis")
                                                                    .build())
                                            .onBackpressureDrop();
                            final Flux<ServerSentEvent<String>> heartbeat =
                                    Flux.interval(Duration.ZERO, HEARTBEAT_INTERVAL)
                                            .map(
                                                    tick ->
                                                            ServerSentEvent.<String>builder()
                                                                    .comment("heartbeat")
                                                                    .build())
                                            .onBackpressureDrop();
                            return ResponseEntity.ok()
                                    .contentType(MediaType.TEXT_EVENT_STREAM)
                                    .body(Flux.merge(events, heartbeat));
                        });
    }

    private String correlationId(final ServerWebExchange exchange) {
        if (exchange == null) return "";
        final String h = exchange.getRequest().getHeaders().getFirst("X-Correlation-Id");
        return h == null ? "" : h;
    }
}
