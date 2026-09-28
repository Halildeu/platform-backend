package com.example.meeting.service;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.TeamsCalendarBridgeProperties;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Re-evaluates current grants; the worker's stored selection is not itself an authorization grant. */
@Service
public class TeamsScheduleAuthorizationService {
    private final TeamsCalendarBridgeProperties properties;
    private final TeamsCalendarTransport transport;
    private final ObjectProvider<OpenFgaAuthzService> authz;
    private final MeetingService meetings;

    public TeamsScheduleAuthorizationService(TeamsCalendarBridgeProperties properties, TeamsCalendarTransport transport,
            ObjectProvider<OpenFgaAuthzService> authz, MeetingService meetings) {
        this.properties = properties; this.transport = transport; this.authz = authz; this.meetings = meetings;
    }

    public void authorize(UUID meetingId, UUID organizerId, TeamsScheduleActor actor) {
        if (meetingId == null || meetingId.equals(new UUID(0, 0)) || organizerId == null || organizerId.equals(new UUID(0, 0))
                || actor == null || !actor.isValid()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_schedule_actor");
        if (!properties.isConfigured()) throw unavailable();
        // This resolver checks the current directory record, account state and protected Microsoft broker link.
        var current = transport.resolve(actor.issuer(), actor.subject());
        if (current == null || !actor.subject().equals(current.subject()) || current.userId() != actor.userId()
                || current.companyId() != actor.companyId() || !organizerId.equals(current.organizerId())
                || !actor.microsoftTenantId().equals(current.tenantId())
                || !properties.getMicrosoftTenantId().equals(actor.microsoftTenantId().toString()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "schedule_identity_changed");
        var permissions = authz.getIfAvailable();
        if (permissions == null || !permissions.isEnabled()) throw unavailable();
        var grant = permissions.checkPrincipalFreshResult("user:" + actor.authzPrincipal(), MeetingAuthz.MANAGER, "module", MeetingAuthz.MODULE);
        if (grant == null || !("granted".equals(grant.reason()) || "no_relation".equals(grant.reason()))) throw unavailable();
        if (!grant.allowed())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "schedule_permission_revoked");
        // Canonical org scope, stable subject CAN_RECORD and active meeting lifecycle are checked again.
        var access = meetings.requireTeamsDispatchAccess(new AdminTenantContext(actor.organizationId(), actor.subject(), actor.authzPrincipal()), meetingId);
        if (access == null || !meetingId.equals(access.meetingId()) || !actor.organizationId().equals(access.orgId())) throw unavailable();
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "schedule_authorization_unavailable");
    }
}
