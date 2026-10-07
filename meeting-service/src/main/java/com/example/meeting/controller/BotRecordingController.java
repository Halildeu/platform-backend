package com.example.meeting.controller;

import com.example.meeting.service.BotRecordingService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.context.annotation.Profile("!local & !dev")
@RequestMapping("/api/v1/meetings/{meetingId}/bot-recording-intents")
public class BotRecordingController {
    private final BotRecordingService service;
    public BotRecordingController(BotRecordingService service) { this.service = service; }
    @GetMapping("/terms") public ResponseEntity<BotRecordingService.Terms> terms(@PathVariable UUID meetingId, @AuthenticationPrincipal Jwt jwt) {
        return ok(service.terms(meetingId, jwt));
    }
    @PostMapping public ResponseEntity<BotRecordingService.View> grant(@PathVariable UUID meetingId, @AuthenticationPrincipal Jwt jwt,
            @RequestBody BotRecordingService.Request request) { return ok(service.grant(meetingId, jwt, request)); }
    @GetMapping("/{intentId}") public ResponseEntity<BotRecordingService.View> status(@PathVariable UUID meetingId, @PathVariable UUID intentId,
            @AuthenticationPrincipal Jwt jwt) { return ok(service.status(meetingId, intentId, jwt)); }
    @DeleteMapping("/{intentId}") public ResponseEntity<BotRecordingService.View> revoke(@PathVariable UUID meetingId, @PathVariable UUID intentId,
            @AuthenticationPrincipal Jwt jwt) { return ok(service.revoke(meetingId, intentId, jwt)); }
    private static <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
