package com.example.user.controller;

import com.example.user.keycloak.KeycloakAdminClient;
import com.example.user.keycloak.MicrosoftOrganizerProperties;
import com.example.user.repository.UserRepository;
import com.example.user.security.ServiceAuthenticationToken;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Trusted services resolve the already-validated user's exact issuer/subject.
 * This proves identity only, not meeting recording rights or Graph permission.
 * No caller-supplied organizer, email, name or company can select another mailbox.
 */
@RestController
@RequestMapping("/api/users/internal")
public class MicrosoftOrganizerInternalController {
    private final UserRepository users;
    private final KeycloakAdminClient keycloak;
    private final MicrosoftOrganizerProperties properties;

    public MicrosoftOrganizerInternalController(UserRepository users, KeycloakAdminClient keycloak,
            MicrosoftOrganizerProperties properties) {
        this.users = users;
        this.keycloak = keycloak;
        this.properties = properties;
    }

    public record ResolveRequest(String issuer, String subject) {}
    public record ResolveResponse(long userId, long companyId, String subject, UUID tenantId, UUID organizerId) {}

    @PostMapping("/microsoft-organizer/resolve")
    public ResponseEntity<?> resolve(@RequestBody ResolveRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof ServiceAuthenticationToken service) || !service.isAuthenticated()
                || service.getAuthorities().stream().noneMatch(a -> "PERM_users:internal".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Service token required");
        }
        if (!properties.isConfiguredFor(keycloak.realm()) || !keycloak.isEnabled()) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE, "microsoft_organizer_unavailable");
        }
        if (request == null || MicrosoftOrganizerProperties.canonicalUuid(request.subject()) == null) {
            return failure(HttpStatus.BAD_REQUEST, "invalid_identity_request");
        }
        if (!properties.getIssuer().equals(request.issuer())) return denied();
        // Subject only; no backfill, email fallback or default company assignment.
        var found = users.findByKcSubject(request.subject());
        if (found.isEmpty()) return denied();
        var user = found.get();
        if (!request.subject().equals(user.getKcSubject()) || !user.isEnabled() || user.getDeletedAt() != null
                || user.getId() == null || user.getCompanyId() == null || user.getCompanyId() <= 0) return denied();
        try {
            var identity = keycloak.fetchMicrosoftIdentity(request.subject(), properties.getProviderAlias());
            if (identity.isEmpty() || !identity.get().tenantId().equals(
                    MicrosoftOrganizerProperties.canonicalUuid(properties.getTenantId()))) return denied();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ResolveResponse(
                    user.getId(), user.getCompanyId(), request.subject(), identity.get().tenantId(), identity.get().objectId()));
        } catch (RuntimeException unavailable) {
            // Do not expose or log upstream representations, tokens or response bodies.
            return failure(HttpStatus.SERVICE_UNAVAILABLE, "microsoft_organizer_unavailable");
        }
    }

    private static ResponseEntity<?> denied() {
        return failure(HttpStatus.FORBIDDEN, "microsoft_organizer_not_linked");
    }

    private static ResponseEntity<?> failure(HttpStatus status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", code));
    }
}
