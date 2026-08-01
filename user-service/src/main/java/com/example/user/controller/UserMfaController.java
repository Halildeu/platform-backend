package com.example.user.controller;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.commonauth.AuthorizationContext;
import com.example.user.authz.AuthorizationContextService;
import com.example.user.keycloak.KeycloakAdminClient;
import com.example.user.model.User;
import com.example.user.permission.PermissionActions;
import com.example.user.repository.UserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

/**
 * Panel MFA section backend (gitops#3211): read a user's second-factor
 * state, reset the TOTP credential, and manage the phone attribute that
 * feeds the SMS OTP lane (gitops#3212). The MFA credential lives in
 * Keycloak, which is exactly why the panel could not show it before — this
 * controller is the narrow, admin-guarded proxy in front of the dedicated
 * {@code user-mfa-admin} Keycloak service account.
 *
 * <p>Authorization mirrors {@code UserControllerV1}'s imperative guard
 * verbatim (superAdmin bypass → JWT authority fast path → /authz/me
 * context), on {@link PermissionActions#USER_UPDATE}: managing a user's
 * second factor is a user-update-class admin operation.
 */
@RestController
@RequestMapping("/api/v1/users/{id:\\d+}/mfa")
public class UserMfaController {

    private static final Logger log = LoggerFactory.getLogger(UserMfaController.class);

    /** Delivery lanes this service owns; TOTP is stock Keycloak and ungoverned. */
    private static final java.util.Set<String> SUPPORTED_METHODS =
            java.util.Set.of("sms", "email");

    private final UserRepository userRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final AuthorizationContextService authorizationContextService;

    public UserMfaController(UserRepository userRepository,
            KeycloakAdminClient keycloakAdminClient,
            AuthorizationContextService authorizationContextService) {
        this.userRepository = userRepository;
        this.keycloakAdminClient = keycloakAdminClient;
        this.authorizationContextService = authorizationContextService;
    }

    public record MfaStatusResponse(boolean requiresMfa, boolean totpConfigured,
            String phoneNumber, boolean smsLaneReady,
            java.util.List<String> allowedMethods) {}

    public record MethodsUpdateRequest(java.util.List<String> methods) {}

    public record RequiredUpdateRequest(Boolean required) {}

    public record PhoneUpdateRequest(
            @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$",
                     message = "phone must be E.164 (+ followed by 8-15 digits)")
            String phone) {}

    @GetMapping
    public ResponseEntity<MfaStatusResponse> status(@PathVariable Long id,
            @RequestHeader(value = "X-Company-Id", required = false) Long companyId) {
        requirePermissionWithCompanyScope(PermissionActions.USER_UPDATE, companyId);
        KeycloakAdminClient.MfaSnapshot snapshot = snapshotFor(id);
        return ResponseEntity.ok(new MfaStatusResponse(
                snapshot.requiresMfa(),
                snapshot.totpConfigured(),
                snapshot.phoneNumber(),
                snapshot.phoneNumber() != null && !snapshot.phoneNumber().isBlank(),
                snapshot.allowedMethods()));
    }

    /**
     * Reset the TOTP credential. Next login re-triggers enrollment if the
     * account carries {@code requires-mfa} — the exact remedy for a lost or
     * stale authenticator.
     */
    @DeleteMapping("/totp")
    public ResponseEntity<Void> resetTotp(@PathVariable Long id,
            @RequestHeader(value = "X-Company-Id", required = false) Long companyId) {
        requirePermissionWithCompanyScope(PermissionActions.USER_UPDATE, companyId);
        KeycloakAdminClient.MfaSnapshot snapshot = snapshotFor(id);
        int deleted = keycloakAdminClient.deleteOtpCredentials(snapshot.kcUserId());
        log.info("mfa-admin: totp reset for user id={} ({} credential(s))", id, deleted);
        return ResponseEntity.noContent().build();
    }

    /** Set (body.phone, E.164) or clear (null body.phone) the SMS phone. */
    @PutMapping("/phone")
    public ResponseEntity<Void> setPhone(@PathVariable Long id,
            @RequestHeader(value = "X-Company-Id", required = false) Long companyId,
            @Valid @RequestBody PhoneUpdateRequest request) {
        requirePermissionWithCompanyScope(PermissionActions.USER_UPDATE, companyId);
        KeycloakAdminClient.MfaSnapshot snapshot = snapshotFor(id);
        keycloakAdminClient.setPhoneAttribute(snapshot.kcUserId(), request.phone());
        return ResponseEntity.noContent().build();
    }

    /**
     * Turn the second-factor requirement on or off for this user.
     *
     * <p>The requirement is not an account flag: the privileged browser flow
     * gates on a role condition, so this assigns or removes the
     * {@code requires-mfa} realm role. Idempotent — asking for the state the
     * user is already in is a no-op, not an error, because a toggle that
     * errors on a double click is worse than one that agrees with reality.
     */
    @PutMapping("/required")
    public ResponseEntity<Void> setRequired(@PathVariable Long id,
            @RequestHeader(value = "X-Company-Id", required = false) Long companyId,
            @RequestBody RequiredUpdateRequest request) {
        requirePermissionWithCompanyScope(PermissionActions.USER_UPDATE, companyId);
        if (request == null || request.required() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "required alanı zorunlu (true/false)");
        }
        KeycloakAdminClient.MfaSnapshot snapshot = snapshotFor(id);
        try {
            boolean changed = keycloakAdminClient.setRequiresMfa(
                    snapshot.kcUserId(), request.required());
            log.info("mfa-admin: requires-mfa set to {} for user id={} (changed={})",
                    request.required(), id, changed);
        } catch (KeycloakAdminClient.RequiresMfaRoleMissingException e) {
            // The realm cannot express the requirement. Reporting success
            // here would leave the operator believing MFA is enforced.
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Restrict which delivery methods this account may use, or lift the
     * restriction with an empty list.
     *
     * <p>Empty means "no restriction" rather than "no methods": the SPI reads
     * an absent attribute the same way, and the safe reading of an ambiguous
     * restriction is the one that cannot lock someone out of their own
     * account. Only the lanes we own are governed — the authenticator app is
     * stock Keycloak and does not consult this list.
     */
    @PutMapping("/methods")
    public ResponseEntity<Void> setMethods(@PathVariable Long id,
            @RequestHeader(value = "X-Company-Id", required = false) Long companyId,
            @RequestBody MethodsUpdateRequest request) {
        requirePermissionWithCompanyScope(PermissionActions.USER_UPDATE, companyId);
        java.util.List<String> methods = request == null || request.methods() == null
                ? java.util.List.of()
                : request.methods();
        for (String m : methods) {
            if (m == null || !SUPPORTED_METHODS.contains(m.trim().toLowerCase(java.util.Locale.ROOT))) {
                // Refusing an unknown method beats storing one: a typo would
                // otherwise narrow the list to nothing the SPI recognises and
                // quietly remove every delivery option.
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "desteklenmeyen yöntem: " + m);
            }
        }
        KeycloakAdminClient.MfaSnapshot snapshot = snapshotFor(id);
        keycloakAdminClient.setAllowedMethods(snapshot.kcUserId(), methods);
        log.info("mfa-admin: allowed methods set to {} for user id={}",
                methods.isEmpty() ? "(unrestricted)" : methods, id);
        return ResponseEntity.noContent().build();
    }

    private KeycloakAdminClient.MfaSnapshot snapshotFor(Long id) {
        if (!keycloakAdminClient.isEnabled()) {
            // Fail-closed disabled surface, never half-working: the dedicated
            // KC client secret has not been provisioned in this environment.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "MFA yönetimi bu ortamda yapılandırılmamış (user-mfa-admin istemcisi yok)");
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Kullanıcı bulunamadı"));
        Optional<KeycloakAdminClient.MfaSnapshot> snapshot =
                keycloakAdminClient.fetchMfaSnapshot(user.getKcSubject(), user.getEmail());
        return snapshot.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Kullanıcının Keycloak hesabı bulunamadı"));
    }

    /** Verbatim mirror of UserControllerV1's guard — see that class. */
    private void requirePermissionWithCompanyScope(String permission, Long companyId) {
        var scope = com.example.commonauth.scope.ScopeContextHolder.get();
        if (scope != null && scope.superAdmin()) {
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması gerekli");
        }

        boolean hasAuthority = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equalsIgnoreCase(permission));
        if (hasAuthority) {
            return;
        }

        Jwt jwt = authentication.getPrincipal() instanceof Jwt j ? j : null;
        AuthorizationContext ctx = authorizationContextService.buildContext(
                jwt, new java.util.ArrayList<>(authentication.getAuthorities()));
        if (!ctx.hasPermission(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Bu işlemi gerçekleştirmek için " + permission + " yetkisine sahip olmalısınız");
        }
        if (companyId != null && !ctx.canAccessCompany(companyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu şirket için yetkiniz yok");
        }
    }
}
