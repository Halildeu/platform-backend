package com.example.meeting.controller;

import com.example.meeting.service.TeamsScheduleActor;
import com.example.meeting.service.TeamsScheduleAuthorizationService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Exact SERVICE permission only; no public user JWT, organizer name or Teams context can authorize dispatch. */
@RestController
@org.springframework.context.annotation.Profile("!local & !dev")
@RequestMapping("/api/v1/internal/meetings/{meetingId}/teams-calendar/authorize")
public class TeamsScheduleAuthorizationInternalController {
    private final TeamsScheduleAuthorizationService service;
    public TeamsScheduleAuthorizationInternalController(TeamsScheduleAuthorizationService service) { this.service = service; }
    public record Request(UUID organizerId, TeamsScheduleActor actor) {}

    @PostMapping
    @PreAuthorize("hasAuthority('SVC_meeting:teams-schedule:authorize') and authentication.name == 'teams-capture-worker' and authentication.token.claims['client_id'] == 'teams-capture-worker'")
    public ResponseEntity<Void> authorize(@PathVariable UUID meetingId, @RequestBody Request request) {
        service.authorize(meetingId, request == null ? null : request.organizerId(), request == null ? null : request.actor());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
