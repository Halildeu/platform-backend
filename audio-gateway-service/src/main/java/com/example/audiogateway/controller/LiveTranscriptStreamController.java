package com.example.audiogateway.controller;

import com.example.audiogateway.dto.LiveTranscriptEvent;
import com.example.audiogateway.service.LiveTranscriptStreamHub;
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
 * Faz 24 İ2-T — live transcript SSE broadcast endpoint.
 *
 * <p>Any authenticated user with {@code meeting:{id}#can_view} may subscribe
 * to the SSE stream. Transcript {@link LiveTranscriptEvent} increments produced
 * by the recorder's WS handshake are fanned out through
 * {@link LiveTranscriptStreamHub} so multiple web viewers see the same live
 * feed the desktop is producing.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li><b>Ephemeral, no replay.</b> Late subscribers only see events after
 *       they connect. Canonical transcript persistence remains
 *       {@code meeting-service}'s job.</li>
 *   <li><b>Owner-gated.</b> {@link MeetingAccessValidator} preflights the
 *       caller's bearer against object-level {@code meeting:{id}#can_view}
 *       before any subscription is granted (401/403/404 without existence
 *       leak; 5xx fail-closed as 503).</li>
 *   <li><b>Bounded heartbeat</b> keeps proxies from tearing the connection
 *       down during idle periods (no transcript for &gt;30s).</li>
 * </ul>
 *
 * <p>Only wired when {@code audio.gateway.direct-stt.live-transcript
 * .broadcast-enabled=true}. When disabled the endpoint does not exist.
 */
@RestController
@ConditionalOnBean(LiveTranscriptStreamHub.class)
@RequestMapping("/api/v1/audio-gateway")
public class LiveTranscriptStreamController {

    private static final Logger log = LoggerFactory.getLogger(LiveTranscriptStreamController.class);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final LiveTranscriptStreamHub hub;
    private final MeetingAccessValidator accessValidator;

    @Autowired
    public LiveTranscriptStreamController(
            final LiveTranscriptStreamHub hub, final MeetingAccessValidator accessValidator) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.accessValidator = Objects.requireNonNull(accessValidator, "accessValidator");
    }

    @GetMapping("/meetings/{meetingId}/live-transcript/stream")
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
                                        "live-transcript SSE denied meetingId={} status={} correlationId={}",
                                        meetingId,
                                        decision.status(),
                                        corrId);
                                return ResponseEntity.status(decision.status()).build();
                            }
                            final Flux<ServerSentEvent<LiveTranscriptEvent>> events =
                                    hub.subscribe(meetingId)
                                            .map(
                                                    result ->
                                                            ServerSentEvent.<LiveTranscriptEvent>builder(result)
                                                                    .event("transcript-chunk")
                                                                    .build())
                                            .onBackpressureDrop();
                            final Flux<ServerSentEvent<LiveTranscriptEvent>> heartbeat =
                                    Flux.interval(Duration.ZERO, HEARTBEAT_INTERVAL)
                                            .map(
                                                    tick ->
                                                            ServerSentEvent.<LiveTranscriptEvent>builder()
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
