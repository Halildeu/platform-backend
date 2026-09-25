package com.example.meeting.controller;

import com.example.meeting.config.TeamsCalendarBridgeProperties;
import com.example.commonauth.openfga.RequireModule;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingService;
import com.example.meeting.service.TeamsCalendarTransport;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** User JWT -> existing canonical recording authorization -> protected Microsoft identity -> private worker. */
@RestController
@RequestMapping("/api/v1/admin/meetings/{meetingId}/teams-calendar")
public class TeamsCalendarController {
    private final MeetingService meetings;
    private final TenantContextResolver tenants;
    private final TeamsCalendarTransport transport;
    private final TeamsCalendarBridgeProperties properties;
    private final Clock clock;

    @Autowired
    public TeamsCalendarController(MeetingService meetings, TenantContextResolver tenants, TeamsCalendarTransport transport,
            TeamsCalendarBridgeProperties properties) {
        this(meetings, tenants, transport, properties, Clock.systemUTC());
    }
    TeamsCalendarController(MeetingService meetings, TenantContextResolver tenants, TeamsCalendarTransport transport,
            TeamsCalendarBridgeProperties properties, Clock clock) {
        this.meetings = meetings; this.tenants = tenants; this.transport = transport; this.properties = properties; this.clock = clock;
    }
    public record BrowseRequest(OffsetDateTime from, OffsetDateTime to) {}
    public record SelectRequest(String eventId) {}

    @PostMapping("/events")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public ResponseEntity<TeamsCalendarTransport.Choices> browse(@PathVariable UUID meetingId, @AuthenticationPrincipal Jwt jwt,
            @RequestBody BrowseRequest request) {
        UUID organizer = authorizedOrganizer(meetingId, jwt);
        if (request == null || request.from() == null || request.to() == null || !request.to().isAfter(request.from())
                || Duration.between(request.from(), request.to()).compareTo(Duration.ofDays(31)) > 0
                || request.from().toInstant().isBefore(clock.instant().minusSeconds(300))
                || request.to().toInstant().isAfter(clock.instant().plusSeconds(90 * 86400L))) throw badRequest();
        var result = transport.browse(organizer, request.from(), request.to());
        if (result == null || result.items() == null || result.items().size() > 500) throw unavailable();
        for (var item : result.items()) {
            if (item == null || !validEvent(item.eventId()) || item.title() == null || item.title().length() > 1024
                    || item.startsAt() == null || item.endsAt() == null || !item.endsAt().isAfter(item.startsAt())
                    || item.startsAt().isBefore(request.from()) || !item.startsAt().isBefore(request.to())) throw unavailable();
        }
        return ok(result);
    }

    @PostMapping("/schedule")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public ResponseEntity<TeamsCalendarTransport.Schedule> select(@PathVariable UUID meetingId, @AuthenticationPrincipal Jwt jwt,
            @RequestBody SelectRequest request) {
        UUID organizer = authorizedOrganizer(meetingId, jwt);
        if (request == null || !validEvent(request.eventId())) throw badRequest();
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(checked(transport.select(organizer, meetingId, request.eventId()), meetingId));
    }

    @GetMapping("/schedule")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public ResponseEntity<TeamsCalendarTransport.Schedule> status(@PathVariable UUID meetingId, @AuthenticationPrincipal Jwt jwt) {
        return ok(checked(transport.status(authorizedOrganizer(meetingId, jwt), meetingId), meetingId));
    }

    @DeleteMapping("/schedule")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public ResponseEntity<Void> cancel(@PathVariable UUID meetingId, @AuthenticationPrincipal Jwt jwt) {
        transport.cancel(authorizedOrganizer(meetingId, jwt), meetingId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private UUID authorizedOrganizer(UUID meetingId, Jwt jwt) {
        if (jwt == null || jwt.getIssuer() == null || jwt.getSubject() == null || jwt.getSubject().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user_token_required");
        if (!properties.isConfigured()) throw unavailable();
        if (jwt.hasClaim("act") || properties.getImpersonationClientId().equals(jwt.getClaimAsString("azp"))) throw forbidden();
        var tenant = tenants.resolveRequired();
        if (!jwt.getSubject().equals(tenant.subject())) throw forbidden();
        // Existing service enforces tenant/org scope and object-level CAN_RECORD, not merely read permission.
        var access = meetings.requireRecordingAccess(tenant, meetingId);
        if (access == null || !meetingId.equals(access.meetingId())) throw forbidden();
        var organizer = transport.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        if (organizer == null || !jwt.getSubject().equals(organizer.subject()) || organizer.userId() <= 0 || organizer.companyId() <= 0
                || organizer.tenantId() == null || !properties.getMicrosoftTenantId().equals(organizer.tenantId().toString())
                || organizer.organizerId() == null || organizer.organizerId().equals(new UUID(0, 0))) throw forbidden();
        // companyId is a legacy numeric namespace, NOT a hash of the canonical organization UUID.
        for (String alias : new String[] {"companyId", "company_id"}) {
            Object company = jwt.getClaim(alias);
            if (company != null && !String.valueOf(organizer.companyId()).equals(company.toString())) throw forbidden();
        }
        return organizer.organizerId();
    }

    private static TeamsCalendarTransport.Schedule checked(TeamsCalendarTransport.Schedule result, UUID meetingId) {
        if (result == null || !meetingId.equals(result.meetingId()) || result.state() == null
                || !Set.of("pending", "dispatching", "joined", "cancelled", "expired", "failed").contains(result.state())
                || result.startsAt() == null || result.endsAt() == null || !result.endsAt().isAfter(result.startsAt())
                || result.failure() != null && !result.failure().matches("[a-z0-9_]{1,100}")) throw unavailable();
        return result;
    }
    private static boolean validEvent(String value) { return value != null && value.matches("[A-Za-z0-9_+=/\\-]{1,2048}"); }
    private static <T> ResponseEntity<T> ok(T result) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result); }
    private static ResponseStatusException badRequest() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_teams_calendar_request"); }
    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "teams_calendar_not_allowed"); }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "teams_calendar_unavailable"); }
}
