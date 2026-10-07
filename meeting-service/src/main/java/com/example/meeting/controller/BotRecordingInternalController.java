package com.example.meeting.controller;

import com.example.meeting.service.BotRecordingService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.context.annotation.Profile("!local & !dev")
@RequestMapping("/api/v1/internal/meetings/{meetingId}/bot-recording/admit")
public class BotRecordingInternalController {
    private final BotRecordingService service;
    public BotRecordingInternalController(BotRecordingService service) { this.service = service; }
    @PostMapping
    @PreAuthorize("hasAuthority('SVC_meeting:bot-recording:admit') and authentication.name == 'teams-capture-worker' and authentication.token.claims['client_id'] == 'teams-capture-worker'")
    public ResponseEntity<BotRecordingService.Admission> admit(@PathVariable UUID meetingId,
            @RequestBody BotRecordingService.AdmissionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.admit(meetingId, request));
    }
}
