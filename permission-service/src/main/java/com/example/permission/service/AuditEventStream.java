package com.example.permission.service;

import com.example.permission.dto.AuditEventResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class AuditEventStream {

    private static final Logger log = LoggerFactory.getLogger(AuditEventStream.class);
    private static final long DEFAULT_TIMEOUT_MS = Duration.ofMinutes(10).toMillis();

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter registerEmitter() {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT_MS);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(error -> {
            log.debug("Audit SSE emitter error, cleaning up: {}", error.getMessage());
            emitters.remove(emitter);
        });

        return emitter;
    }

    public void publish(AuditEventResponse event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .id(event.id())
                        .name("audit-event")
                        .data(event, MediaType.APPLICATION_JSON));
            } catch (IOException ex) {
                log.debug("Audit SSE emitter send failed, removing emitter: {}", ex.getMessage());
                emitter.completeWithError(ex);
                emitters.remove(emitter);
            }
        }
    }
}

