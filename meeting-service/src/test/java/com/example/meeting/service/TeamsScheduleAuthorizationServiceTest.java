package com.example.meeting.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.TeamsCalendarBridgeProperties;
import com.example.meeting.dto.MeetingRecordingAccessResponse;
import com.example.meeting.security.AdminTenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class TeamsScheduleAuthorizationServiceTest {
    private final UUID meeting = UUID.randomUUID(), tenant = UUID.randomUUID(), microsoftTenant = UUID.randomUUID(), organizer = UUID.randomUUID();
    private final TeamsScheduleActor actor = new TeamsScheduleActor(1, "https://login.example/realms/platform", "stable-subject", tenant, microsoftTenant, "42", 42, 35);
    private final TeamsCalendarTransport transport = mock(TeamsCalendarTransport.class);
    private final OpenFgaAuthzService permissions = mock(OpenFgaAuthzService.class);
    private final MeetingService meetings = mock(MeetingService.class);
    private final TeamsCalendarBridgeProperties properties = new TeamsCalendarBridgeProperties();
    private TeamsScheduleAuthorizationService service;
    private TeamsCalendarTransport.Organizer identity;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        ObjectProvider<OpenFgaAuthzService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(permissions);
        properties.setEnabled(true); properties.setControlKey("k".repeat(32)); properties.setMicrosoftTenantId(microsoftTenant.toString());
        identity = new TeamsCalendarTransport.Organizer(42, 35, actor.subject(), microsoftTenant, organizer);
        when(transport.resolve(actor.issuer(), actor.subject())).thenReturn(identity);
        when(permissions.isEnabled()).thenReturn(true);
        when(permissions.checkPrincipalFreshResult("user:42", "can_manage", "module", "MEETING")).thenReturn(new OpenFgaAuthzService.CheckResult(true, "granted"));
        when(meetings.requireTeamsDispatchAccess(new AdminTenantContext(tenant, actor.subject(), "42"), meeting))
                .thenReturn(new MeetingRecordingAccessResponse(meeting, tenant, tenant, List.of()));
        service = new TeamsScheduleAuthorizationService(properties, transport, provider, meetings);
    }
    @Test void checksCurrentDirectoryThenModuleThenCanonicalObjectAndLifecycle() {
        service.authorize(meeting, organizer, actor);
        var order = inOrder(transport, permissions, meetings);
        order.verify(transport).resolve(actor.issuer(), actor.subject());
        order.verify(permissions).isEnabled();
        order.verify(permissions).checkPrincipalFreshResult("user:42", "can_manage", "module", "MEETING");
        order.verify(meetings).requireTeamsDispatchAccess(new AdminTenantContext(tenant, actor.subject(), "42"), meeting);
    }
    @Test void rechecksEveryAttemptAndDeniesRevokedModulePermission() {
        service.authorize(meeting, organizer, actor);
        when(permissions.checkPrincipalFreshResult("user:42", "can_manage", "module", "MEETING")).thenReturn(new OpenFgaAuthzService.CheckResult(false, "no_relation"));
        assertStatus(403, () -> service.authorize(meeting, organizer, actor));
        verify(transport, times(2)).resolve(actor.issuer(), actor.subject());
        verify(meetings, times(1)).requireTeamsDispatchAccess(any(), eq(meeting));
    }
    @Test void refusesDisabledBridgeOrDisabledPermissionService() {
        properties.setEnabled(false);
        assertStatus(503, () -> service.authorize(meeting, organizer, actor)); verifyNoInteractions(transport);
        properties.setEnabled(true); when(permissions.isEnabled()).thenReturn(false);
        assertStatus(503, () -> service.authorize(meeting, organizer, actor)); verifyNoInteractions(meetings);
    }
    @ParameterizedTest @ValueSource(ints = {403, 404, 503}) void propagatesCurrentIdentityDenialOrUnavailability(int status) {
        when(transport.resolve(anyString(), anyString())).thenThrow(new ResponseStatusException(HttpStatus.valueOf(status)));
        assertStatus(status, () -> service.authorize(meeting, organizer, actor)); verifyNoInteractions(permissions, meetings);
    }
    @Test void refusesChangedSubjectCompanyDirectoryIdOrganizerOrMicrosoftTenant() {
        for (var changed : List.of(
                new TeamsCalendarTransport.Organizer(42, 35, "other", microsoftTenant, organizer),
                new TeamsCalendarTransport.Organizer(43, 35, actor.subject(), microsoftTenant, organizer),
                new TeamsCalendarTransport.Organizer(42, 36, actor.subject(), microsoftTenant, organizer),
                new TeamsCalendarTransport.Organizer(42, 35, actor.subject(), UUID.randomUUID(), organizer),
                new TeamsCalendarTransport.Organizer(42, 35, actor.subject(), microsoftTenant, UUID.randomUUID()))) {
            when(transport.resolve(anyString(), anyString())).thenReturn(changed);
            assertStatus(403, () -> service.authorize(meeting, organizer, actor));
        }
        verifyNoInteractions(permissions, meetings);
    }
    @ParameterizedTest @ValueSource(ints = {403, 404, 409, 503}) void refusesRevokedRecordingForeignDeletedCancelledOrUnavailableMeeting(int status) {
        when(meetings.requireTeamsDispatchAccess(any(), any())).thenThrow(new ResponseStatusException(HttpStatus.valueOf(status)));
        assertStatus(status, () -> service.authorize(meeting, organizer, actor));
    }
    @Test void missingActorOrUnboundAuthzPrincipalCannotReachDirectory() {
        assertStatus(400, () -> service.authorize(meeting, organizer, null));
        var arbitrary = new TeamsScheduleActor(1, actor.issuer(), actor.subject(), tenant, microsoftTenant, "someone-else", 42, 35);
        assertStatus(400, () -> service.authorize(meeting, organizer, arbitrary)); verifyNoInteractions(transport, permissions, meetings);
    }
    @Test void acceptsStableSubjectModulePrincipalWithoutHashingCanonicalOrg() {
        var stable = new TeamsScheduleActor(1, actor.issuer(), actor.subject(), tenant, microsoftTenant, actor.subject(), 42, 35);
        when(permissions.checkPrincipalFreshResult("user:" + actor.subject(), "can_manage", "module", "MEETING")).thenReturn(new OpenFgaAuthzService.CheckResult(true, "granted"));
        when(meetings.requireTeamsDispatchAccess(new AdminTenantContext(tenant, actor.subject(), actor.subject()), meeting))
                .thenReturn(new MeetingRecordingAccessResponse(meeting, tenant, tenant, List.of()));
        service.authorize(meeting, organizer, stable);
    }
    @Test void rejectsInvalidIssuerAndZeroTenant() {
        for (String issuer : List.of("http://login.example", "https://user:secret@login.example", "https://login.example?x=y", "https://login.example#x", "invalid"))
            assertFalse(new TeamsScheduleActor(1, issuer, actor.subject(), tenant, microsoftTenant, "42", 42, 35).isValid());
        assertFalse(new TeamsScheduleActor(1, actor.issuer(), actor.subject(), new UUID(0, 0), microsoftTenant, "42", 42, 35).isValid());
    }
    @Test void unavailableModuleCheckDefersRatherThanReportsRevocation() {
        when(permissions.checkPrincipalFreshResult("user:42", "can_manage", "module", "MEETING"))
                .thenReturn(new OpenFgaAuthzService.CheckResult(false, "unavailable"));
        assertStatus(503, () -> service.authorize(meeting, organizer, actor)); verifyNoInteractions(meetings);
    }
    private static void assertStatus(int status, Runnable action) {
        assertEquals(status, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
}
