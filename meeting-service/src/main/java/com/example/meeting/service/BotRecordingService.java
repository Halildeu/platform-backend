package com.example.meeting.service;

import static com.example.common.meeting.bot.BotRecordingContract.*;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.BotRecordingProperties;
import com.example.meeting.config.TeamsCalendarBridgeProperties;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** User intent and worker admission, deliberately separate from mobile recording and from ongoing PCM permission. */
@Service
public class BotRecordingService {
    private final BotRecordingProperties properties;
    private final TeamsCalendarBridgeProperties calendar;
    private final TenantContextResolver tenants;
    private final MeetingService meetings;
    private final TeamsCalendarTransport directory;
    private final ObjectProvider<OpenFgaAuthzService> authz;
    private final TeamsScheduleAuthorizationService dispatch;
    private final BotRecordingTransport owner;
    private final Environment environment;
    private final Clock clock;

    @Autowired
    public BotRecordingService(BotRecordingProperties properties, TeamsCalendarBridgeProperties calendar,
            TenantContextResolver tenants, MeetingService meetings, TeamsCalendarTransport directory,
            ObjectProvider<OpenFgaAuthzService> authz, TeamsScheduleAuthorizationService dispatch,
            BotRecordingTransport owner, Environment environment) {
        this(properties, calendar, tenants, meetings, directory, authz, dispatch, owner, environment, Clock.systemUTC());
    }
    BotRecordingService(BotRecordingProperties properties, TeamsCalendarBridgeProperties calendar,
            TenantContextResolver tenants, MeetingService meetings, TeamsCalendarTransport directory,
            ObjectProvider<OpenFgaAuthzService> authz, TeamsScheduleAuthorizationService dispatch,
            BotRecordingTransport owner, Environment environment, Clock clock) {
        this.properties = properties; this.calendar = calendar; this.tenants = tenants; this.meetings = meetings;
        this.directory = directory; this.authz = authz; this.dispatch = dispatch; this.owner = owner;
        this.environment = environment; this.clock = clock;
    }
    public record Terms(String version, String text, String sha256, String locale, int maximumLifetimeSeconds) {}
    public record Request(UUID requestKey, String consentVersion, String consentTextHash, String locale, Instant expiresAt) {}
    /** No directory identity, service token, or Microsoft call identifiers in browser responses. */
    public record View(UUID intentId, UUID meetingId, UUID requestKey, String state, long revision,
                       Instant createdAt, Instant expiresAt, Instant revokedAt, String consentVersion, String consentTextHash) {}
    public record AdmissionRequest(UUID intentId, String callId, String mediaSessionId) {}
    public record Admission(UUID intentId, UUID meetingId, long revision, Instant expiresAt, Binding binding,
                            boolean ongoingAudioAuthorized) {}

    public Terms terms(UUID meetingId, Jwt jwt) { authorizedOwner(meetingId, jwt); return configuredTerms(); }

    public View grant(UUID meetingId, Jwt jwt, Request request) {
        user(jwt);
        if (!uuid(meetingId) || request == null || !uuid(request.requestKey()) || request.expiresAt() == null
                || !request.expiresAt().equals(request.expiresAt().truncatedTo(ChronoUnit.MICROS))) throw invalid();
        Snapshot prior = null;
        try {
            prior = checked(owner.findRequest(new RequestRef(request.requestKey(), meetingId, jwt.getIssuer().toString(), jwt.getSubject())), meetingId, null);
        } catch (ResponseStatusException notFound) {
            if (notFound.getStatusCode().value() != 404) throw notFound;
        }
        if (prior != null) {
            var original = prior.grant();
            if (!jwt.getIssuer().toString().equals(original.owner().issuer()) || !jwt.getSubject().equals(original.owner().subject())
                    || !request.requestKey().equals(original.requestKey()) || !Objects.equals(request.consentVersion(), original.consentVersion())
                    || !Objects.equals(request.consentTextHash(), original.consentTextHash()) || !Objects.equals(request.locale(), original.locale())
                    || !request.expiresAt().equals(original.expiresAt())) throw conflict();
            return view(prior); // Historical result only; never renews consent or admits audio.
        }
        Owner actor = authorizedOwner(meetingId, jwt);
        Terms terms = configuredTerms();
        if (!terms.version().equals(request.consentVersion()) || !terms.sha256().equals(request.consentTextHash())
                || !terms.locale().equals(request.locale())) throw conflict();
        Instant now = clock.instant();
        if (!request.expiresAt().isAfter(now) || request.expiresAt().isAfter(now.plusSeconds(86400))) throw invalid();
        // Absolute expiry is supplied with the shown terms and must be retained on timeout retries.
        // The owner applies the 24h bound ONLY on creation, preserving exact retry semantics after expiry.
        var command = new Grant(request.requestKey(), meetingId, actor, terms.version(), terms.sha256(), terms.locale(), request.expiresAt());
        var result = checked(owner.grant(command), meetingId, null);
        if (!command.equals(result.grant())) throw unavailable();
        return view(result);
    }

    public View status(UUID meetingId, UUID intentId, Jwt jwt) { return view(owned(meetingId, intentId, jwt)); }
    public View revoke(UUID meetingId, UUID intentId, Jwt jwt) {
        var prior = owned(meetingId, intentId, jwt);
        var result = checked(owner.revoke(new Lookup(intentId, OwnerKey.of(prior.grant().owner()), meetingId)), meetingId, intentId);
        if (!prior.grant().equals(result.grant()) || !"REVOKED".equals(result.state())) throw unavailable();
        return view(result);
    }
    private Snapshot owned(UUID meetingId, UUID intentId, Jwt jwt) {
        user(jwt);
        if (!uuid(meetingId) || !uuid(intentId)) throw invalid();
        var snapshot = checked(owner.inspect(new IntentRef(intentId, meetingId)), meetingId, intentId);
        var historical = snapshot.grant().owner();
        if (!jwt.getIssuer().toString().equals(historical.issuer()) || !jwt.getSubject().equals(historical.subject())) throw missing();
        // No fresh directory/module/CAN_RECORD gate: the original owner must always be able to withdraw.
        return snapshot;
    }

    public Admission admit(UUID meetingId, AdmissionRequest request) {
        if (!properties.isEnabled()) throw unavailable();
        if (!uuid(meetingId) || request == null || !uuid(request.intentId()) || !identifier(request.callId())
                || !identifier(request.mediaSessionId())) throw invalid();
        var stored = checked(owner.inspect(new IntentRef(request.intentId(), meetingId)), meetingId, request.intentId());
        if ("REVOKED".equals(stored.state()) || !stored.grant().expiresAt().isAfter(clock.instant())) throw conflict();
        var actor = stored.grant().owner();
        dispatch.authorize(meetingId, actor.organizerId(), new TeamsScheduleActor(1, actor.issuer(), actor.subject(),
                actor.orgId(), actor.microsoftTenantId(), actor.authzPrincipal(), actor.userId(), actor.companyId()));
        var binding = new Binding("teams-capture-worker", request.callId(), request.mediaSessionId());
        var result = checked(owner.bind(new Bind(request.intentId(), actor, meetingId, 1, binding)), meetingId, request.intentId());
        if (!stored.grant().equals(result.grant()) || !"BOUND".equals(result.state()) || !binding.equals(result.binding())
                || !result.grant().expiresAt().isAfter(clock.instant())) throw unavailable();
        // This records a trusted worker assertion. Microsoft call-to-meeting proof and an ingest revocation fence remain separate.
        return new Admission(result.intentId(), meetingId, result.revision(), result.grant().expiresAt(), result.binding(), false);
    }

    private Owner authorizedOwner(UUID meetingId, Jwt jwt) {
        user(jwt);
        if (!properties.isEnabled() || !calendar.isConfigured()) throw unavailable();
        if (!uuid(meetingId)) throw invalid();
        var tenant = tenants.resolveRequired();
        if (tenant == null || !jwt.getSubject().equals(tenant.subject())) throw forbidden();
        // @RequireModule intercepts admin routes only. This public-user facade therefore checks explicitly and fail-closed.
        var permissions = authz.getIfAvailable();
        if (permissions == null || !permissions.isEnabled()) throw unavailable();
        var decision = permissions.checkPrincipalFreshResult("user:" + tenant.authzPrincipal(), MeetingAuthz.MANAGER, "module", MeetingAuthz.MODULE);
        if (decision == null || !("granted".equals(decision.reason()) || "no_relation".equals(decision.reason()))) throw unavailable();
        if (!decision.allowed()) throw forbidden();
        var access = meetings.requireTeamsDispatchAccess(tenant, meetingId);
        if (access == null || !meetingId.equals(access.meetingId()) || !tenant.tenantId().equals(access.orgId())
                || !uuid(access.tenantId()) || !uuid(access.orgId())) throw forbidden();
        var identity = directory.resolve(jwt.getIssuer().toString(), jwt.getSubject());
        if (identity == null || !jwt.getSubject().equals(identity.subject()) || identity.userId() <= 0 || identity.companyId() <= 0
                || identity.tenantId() == null || !calendar.getMicrosoftTenantId().equals(identity.tenantId().toString())
                || !uuid(identity.organizerId())) throw forbidden();
        for (String alias : new String[] { "companyId", "company_id" }) {
            Object company = jwt.getClaim(alias);
            if (company != null && !String.valueOf(identity.companyId()).equals(company.toString())) throw forbidden();
        }
        var actor = new TeamsScheduleActor(1, jwt.getIssuer().toString(), jwt.getSubject(), access.orgId(), identity.tenantId(),
                tenant.authzPrincipal(), identity.userId(), identity.companyId());
        if (!actor.isValid() || actor.issuer().length() > 512) throw forbidden();
        return new Owner(identity.companyId(), identity.userId(), actor.issuer(), actor.subject(), access.tenantId(), access.orgId(),
                actor.authzPrincipal(), actor.microsoftTenantId(), identity.organizerId());
    }
    private void user(Jwt jwt) {
        // The default resource-server chain also accepts SERVICE tokens; restrict this facade to the configured USER issuer.
        String issuer = environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                environment.getProperty("SECURITY_JWT_ISSUER", "http://localhost:8081/realms/serban"));
        if (jwt == null || jwt.getIssuer() == null || !issuer.equals(jwt.getIssuer().toString())
                || jwt.getSubject() == null || jwt.getSubject().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user_token_required");
        if (jwt.hasClaim("act") || Objects.equals(calendar.getImpersonationClientId(), jwt.getClaimAsString("azp"))) throw forbidden();
    }
    private Terms configuredTerms() {
        String text = properties.getConsentText(), version = properties.getConsentVersion(), locale = properties.getLocale();
        if (text == null || text.isBlank() || text.length() > 16384 || version == null || !version.matches("[A-Za-z0-9_.:-]{1,100}")
                || locale == null || !locale.matches("[A-Za-z0-9-]{1,35}")) throw unavailable();
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            return new Terms(version, text, hash, locale, 86400);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Snapshot checked(Snapshot result, UUID meeting, UUID intent) {
        if (result == null || result.schemaVersion() != 1 || !"TEAMS_LIVE_TRANSCRIPTION".equals(result.purpose())
                || !uuid(result.intentId()) || intent != null && !intent.equals(result.intentId()) || result.grant() == null
                || !meeting.equals(result.grant().meetingId()) || result.grant().owner() == null || result.grant().expiresAt() == null
                || result.createdAt() == null || !Set.of("GRANTED", "BOUND", "REVOKED").contains(Objects.toString(result.state(), ""))
                || result.revision() < 1 || result.revision() > 3
                || "GRANTED".equals(result.state()) && (result.revision() != 1 || result.binding() != null || result.revokedAt() != null)
                || "BOUND".equals(result.state()) && (result.revision() != 2 || result.binding() == null || result.revokedAt() != null)
                || "REVOKED".equals(result.state()) && (result.revokedAt() == null || result.revision() != (result.binding() == null ? 2 : 3)))
            throw unavailable();
        return result;
    }
    private static View view(Snapshot s) { return new View(s.intentId(), s.grant().meetingId(), s.grant().requestKey(), s.state(), s.revision(),
            s.createdAt(), s.grant().expiresAt(), s.revokedAt(), s.grant().consentVersion(), s.grant().consentTextHash()); }
    private static boolean uuid(UUID value) { return value != null && !value.equals(new UUID(0, 0)); }
    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9_.:-]{1,128}"); }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_bot_recording_request"); }
    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "bot_recording_not_allowed"); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "bot_recording_not_found"); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT, "bot_recording_conflict"); }
    private static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "bot_recording_unavailable"); }
}
