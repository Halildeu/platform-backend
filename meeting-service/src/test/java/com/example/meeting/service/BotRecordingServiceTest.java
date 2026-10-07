package com.example.meeting.service;

import static com.example.common.meeting.bot.BotRecordingContract.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.BotRecordingProperties;
import com.example.meeting.config.TeamsCalendarBridgeProperties;
import com.example.meeting.dto.MeetingRecordingAccessResponse;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.TenantContextResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

class BotRecordingServiceTest {
    private final UUID meeting = UUID.randomUUID(), tenant = UUID.randomUUID(), org = UUID.randomUUID(),
            msTenant = UUID.randomUUID(), organizer = UUID.randomUUID(), intent = UUID.randomUUID(), key = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-07T10:00:00Z");
    private final String issuer = "https://login.example/realms/platform";
    private final BotRecordingProperties properties = new BotRecordingProperties();
    private final TeamsCalendarBridgeProperties calendar = new TeamsCalendarBridgeProperties();
    private final TenantContextResolver tenants = mock(TenantContextResolver.class);
    private final MeetingService meetings = mock(MeetingService.class);
    private final TeamsCalendarTransport directory = mock(TeamsCalendarTransport.class);
    private final OpenFgaAuthzService permissions = mock(OpenFgaAuthzService.class);
    private final TeamsScheduleAuthorizationService dispatch = mock(TeamsScheduleAuthorizationService.class);
    private final BotRecordingTransport transport = mock(BotRecordingTransport.class);
    private final AdminTenantContext context = new AdminTenantContext(org, "subject", "42");
    private BotRecordingService service;
    private Owner actor;
    private BotRecordingService.Request request;
    private Grant grant;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        properties.setEnabled(true); properties.setConsentVersion("v1"); properties.setConsentText("Approved test terms.");
        calendar.setEnabled(true); calendar.setControlKey("k".repeat(32)); calendar.setMicrosoftTenantId(msTenant.toString());
        when(tenants.resolveRequired()).thenReturn(context);
        when(permissions.isEnabled()).thenReturn(true);
        when(permissions.checkPrincipalFreshResult("user:42", "can_manage", "module", "MEETING"))
                .thenReturn(new OpenFgaAuthzService.CheckResult(true, "granted"));
        when(meetings.requireTeamsDispatchAccess(context, meeting)).thenReturn(new MeetingRecordingAccessResponse(meeting, tenant, org, List.of()));
        when(directory.resolve(issuer, "subject")).thenReturn(new TeamsCalendarTransport.Organizer(42, 35, "subject", msTenant, organizer));
        ObjectProvider<OpenFgaAuthzService> provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(permissions);
        service = new BotRecordingService(properties, calendar, tenants, meetings, directory, provider, dispatch, transport,
                new MockEnvironment().withProperty("SECURITY_JWT_ISSUER", issuer), Clock.fixed(now, ZoneOffset.UTC));
        var terms = service.terms(meeting, user());
        clearInvocations(tenants, permissions, meetings, directory);
        request = new BotRecordingService.Request(key, terms.version(), terms.sha256(), terms.locale(), now.plusSeconds(3600));
        actor = new Owner(35, 42, issuer, "subject", tenant, org, "42", msTenant, organizer);
        grant = new Grant(key, meeting, actor, terms.version(), terms.sha256(), terms.locale(), request.expiresAt());
        when(transport.findRequest(any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        when(transport.grant(any())).thenAnswer(call -> snapshot(call.getArgument(0), "GRANTED"));
        when(transport.inspect(new IntentRef(intent, meeting))).thenReturn(snapshot(grant, "GRANTED"));
    }
    @Test void grantDerivesIdentityAndScopeAndChecksFreshModuleAndObjectBeforeWriting() {
        var result = service.grant(meeting, user(), request);
        assertEquals(intent, result.intentId()); assertEquals(meeting, result.meetingId());
        var order = inOrder(permissions, meetings, directory, transport);
        order.verify(transport).findRequest(new RequestRef(key, meeting, issuer, "subject"));
        order.verify(permissions).isEnabled();
        order.verify(permissions).checkPrincipalFreshResult("user:42", "can_manage", "module", "MEETING");
        order.verify(meetings).requireTeamsDispatchAccess(context, meeting);
        order.verify(directory).resolve(issuer, "subject"); order.verify(transport).grant(grant);
        service.grant(meeting, user(), request);
        verify(permissions, times(2)).checkPrincipalFreshResult("user:42", "can_manage", "module", "MEETING");
    }
    @Test void moduleEnforcementDoesNotDependOnAdminInterceptorOrFailOpenConfiguration() {
        when(permissions.isEnabled()).thenReturn(false);
        assertStatus(503, () -> service.grant(meeting, user(), request));
        verifyNoInteractions(meetings, directory); verify(transport, never()).grant(any());
        when(permissions.isEnabled()).thenReturn(true);
        when(permissions.checkPrincipalFreshResult(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new OpenFgaAuthzService.CheckResult(false, "no_relation"));
        assertStatus(403, () -> service.grant(meeting, user(), request));
        verifyNoInteractions(meetings, directory);
    }
    @ParameterizedTest @ValueSource(ints = {403, 404, 409, 503})
    void objectPermissionScopeLifecycleAndUnavailableChecksPreventGrant(int status) {
        when(meetings.requireTeamsDispatchAccess(any(), any())).thenThrow(new ResponseStatusException(HttpStatus.valueOf(status)));
        assertStatus(status, () -> service.grant(meeting, user(), request)); verifyNoInteractions(directory);
        verify(transport, never()).grant(any());
    }
    @Test void termsMustExactlyMatchAndCannotInventActorFromCompanyClaim() {
        var changed = new BotRecordingService.Request(key, "v0", request.consentTextHash(), request.locale(), request.expiresAt());
        assertStatus(409, () -> service.grant(meeting, user(), changed)); verify(transport, never()).grant(any());
        Jwt wrong = Jwt.withTokenValue("x").header("alg", "RS256").issuer(issuer).subject("subject").claim("companyId", 999).build();
        assertStatus(403, () -> service.grant(meeting, wrong, request));
    }
    @Test void blankTermsOrDisabledGrantRemainClosed() {
        properties.setConsentText(""); assertStatus(503, () -> service.grant(meeting, user(), request));
        properties.setEnabled(false); assertStatus(503, () -> service.grant(meeting, user(), request));
        verify(transport, never()).grant(any());
    }
    @ParameterizedTest @ValueSource(longs = {-1, 0, 86401})
    void newIntentExpiryOutsideCreationWindowIsClientError(long seconds) {
        var invalid = new BotRecordingService.Request(key, request.consentVersion(), request.consentTextHash(), request.locale(), now.plusSeconds(seconds));
        assertStatus(400, () -> service.grant(meeting, user(), invalid)); verify(transport, never()).grant(any());
    }
    @Test void lostResponseRetryAfterTermsDirectoryAndPermissionChangesReturnsSameHistoricalIntent() {
        doReturn(snapshot(grant, "REVOKED")).when(transport).findRequest(any());
        properties.setEnabled(false); properties.setConsentVersion("v2"); properties.setConsentText("Changed terms");
        var result = service.grant(meeting, user(), request);
        assertEquals("REVOKED", result.state()); assertEquals(request.expiresAt(), result.expiresAt());
        verifyNoInteractions(tenants, permissions, meetings, directory, dispatch); verify(transport, never()).grant(any());
        var renewed = new BotRecordingService.Request(key, request.consentVersion(), request.consentTextHash(), request.locale(), now.plusSeconds(7200));
        assertStatus(409, () -> service.grant(meeting, user(), renewed));
    }
    @Test void originalOwnerCanWithdrawDespiteLostPermissionsLinkOrDisabledNewGrants() {
        properties.setEnabled(false); calendar.setEnabled(false);
        when(transport.revoke(new Lookup(intent, OwnerKey.of(actor), meeting))).thenReturn(snapshot(grant, "REVOKED"));
        assertEquals("GRANTED", service.status(meeting, intent, user()).state());
        assertEquals("REVOKED", service.revoke(meeting, intent, user()).state());
        verifyNoInteractions(tenants, permissions, meetings, directory, dispatch);
    }
    @Test void expiredHistoricalRetryIsRecoveredWithoutRenewalOrFreshPermissionChecks() {
        Instant expiredAt = now.minusSeconds(30);
        var historical = new Grant(key, meeting, actor, grant.consentVersion(), grant.consentTextHash(), grant.locale(), expiredAt);
        doReturn(snapshot(historical, "GRANTED")).when(transport).findRequest(any());
        properties.setEnabled(false);
        var retry = new BotRecordingService.Request(key, request.consentVersion(), request.consentTextHash(), request.locale(), expiredAt);
        assertEquals(expiredAt, service.grant(meeting, user(), retry).expiresAt());
        verifyNoInteractions(tenants, permissions, meetings, directory, dispatch); verify(transport, never()).grant(any());
    }
    @Test void foreignOwnerCannotReadOrRevokeEvenIfAbleToRecordThisMeeting() {
        Jwt foreign = Jwt.withTokenValue("x").header("alg", "RS256").issuer(issuer).subject("someone-else").build();
        assertStatus(404, () -> service.status(meeting, intent, foreign));
        assertStatus(404, () -> service.revoke(meeting, intent, foreign)); verify(transport, never()).revoke(any());
    }
    @Test void serviceIssuerAndImpersonationCannotActAsUser() {
        Jwt serviceToken = Jwt.withTokenValue("x").header("alg", "RS256").issuer("https://service.example").subject("subject").build();
        assertStatus(401, () -> service.status(meeting, intent, serviceToken));
        Jwt delegated = Jwt.withTokenValue("x").header("alg", "RS256").issuer(issuer).subject("subject").claim("act", "another").build();
        assertStatus(403, () -> service.revoke(meeting, intent, delegated)); verifyNoInteractions(transport);
    }
    @Test void admissionLoadsStoredActorAndFreshAuthorizationBeforeAtomicBinding() {
        when(transport.bind(any())).thenReturn(snapshot(grant, "BOUND"));
        var result = service.admit(meeting, new BotRecordingService.AdmissionRequest(intent, "call-1", "media-1"));
        assertFalse(result.ongoingAudioAuthorized()); assertEquals(2, result.revision());
        var order = inOrder(transport, dispatch);
        order.verify(transport).inspect(new IntentRef(intent, meeting));
        order.verify(dispatch).authorize(meeting, organizer, new TeamsScheduleActor(1, issuer, "subject", org, msTenant, "42", 42, 35));
        order.verify(transport).bind(new Bind(intent, actor, meeting, 1, binding()));
    }
    @Test void revokedExpiredAndDisabledIntentCannotAdmit() {
        when(transport.inspect(any())).thenReturn(snapshot(grant, "REVOKED"));
        assertStatus(409, () -> service.admit(meeting, admissionRequest()));
        Grant expired = new Grant(key, meeting, actor, grant.consentVersion(), grant.consentTextHash(), grant.locale(), now);
        when(transport.inspect(any())).thenReturn(snapshot(expired, "GRANTED"));
        assertStatus(409, () -> service.admit(meeting, admissionRequest()));
        properties.setEnabled(false); assertStatus(503, () -> service.admit(meeting, admissionRequest()));
        verifyNoInteractions(dispatch); verify(transport, never()).bind(any());
    }
    @Test void revocationBetweenFreshCheckAndBindingCannotProduceAdmission() {
        when(transport.bind(any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));
        assertStatus(409, () -> service.admit(meeting, admissionRequest()));
    }
    @Test void freshDirectoryOrPermissionFailureCannotBind() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(dispatch).authorize(any(), any(), any());
        assertStatus(403, () -> service.admit(meeting, admissionRequest())); verify(transport, never()).bind(any());
    }
    @Test void foreignUpstreamMeetingOrBindingCannotBeAccepted() {
        var other = new Grant(key, UUID.randomUUID(), actor, grant.consentVersion(), grant.consentTextHash(), grant.locale(), grant.expiresAt());
        when(transport.inspect(any())).thenReturn(snapshot(other, "GRANTED"));
        assertStatus(503, () -> service.status(meeting, intent, user()));
        when(transport.inspect(any())).thenReturn(snapshot(grant, "GRANTED"));
        var wrong = new Snapshot(1, "TEAMS_LIVE_TRANSCRIPTION", intent, grant, "BOUND", 2, now,
                new Binding("teams-capture-worker", "other", "media-1"), null);
        when(transport.bind(any())).thenReturn(wrong);
        assertStatus(503, () -> service.admit(meeting, admissionRequest()));
    }
    private Snapshot snapshot(Grant g, String state) {
        return new Snapshot(1, "TEAMS_LIVE_TRANSCRIPTION", intent, g, state, "GRANTED".equals(state) ? 1 : 2, now,
                "BOUND".equals(state) ? binding() : null, "REVOKED".equals(state) ? now : null);
    }
    private Binding binding() { return new Binding("teams-capture-worker", "call-1", "media-1"); }
    private BotRecordingService.AdmissionRequest admissionRequest() { return new BotRecordingService.AdmissionRequest(intent, "call-1", "media-1"); }
    private Jwt user() { return Jwt.withTokenValue("verified").header("alg", "RS256").issuer(issuer).subject("subject").build(); }
    private static void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(ResponseStatusException.class, action::run).getStatusCode().value());
    }
}
