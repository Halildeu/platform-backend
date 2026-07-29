package com.example.ethics.security;

import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Server-side permission-service boundary for every staff request.
 *
 * <p>The Keycloak role/scope proves that the token may ask for the product;
 * current permission-service state decides whether it still has ETHIC=MANAGE.
 * Transport, HTTP, decode, identity, and projection failures all deny access.
 *
 * <p><b>The caller still learns nothing.</b> Every path returns the same {@code false} and
 * the HTTP response is unchanged — dependency health is not disclosed and a previous allow
 * is never retained. What changed is that the server now writes down <em>which</em> link was
 * missing.
 *
 * <p>That distinction is not cosmetic. "permission-service is unreachable" and "this person
 * has no ETIK_SPEAK_MANAGER role" are operationally opposite — one is an outage, the other a
 * provisioning gap — and until now both surfaced as an identical silent 403 with nothing in
 * the log. Authorizing a real handler takes six separate steps across three systems; when one
 * is missed the only symptom was a 403 that named nothing, so the diagnosis cost hours.
 */
@Component
public class EthicsEntitlementVerifier {
    private static final Logger log = LoggerFactory.getLogger(EthicsEntitlementVerifier.class);
    private final RestClient permissionService;

    @Autowired
    public EthicsEntitlementVerifier(
            RestClient.Builder builder,
            @Value("${ethics.permission-service-base-url:http://permission-service:8090}") String baseUrl,
            @Value("${ethics.permission-service-timeout:PT3S}") Duration timeout) {
        var httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.permissionService = builder.clone()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
    }

    EthicsEntitlementVerifier(RestClient permissionService) {
        this.permissionService = permissionService;
    }

    public boolean hasManageEntitlement(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            log.debug("Etik Speak yetki reddi: {}", Denial.NO_BEARER);
            return false;
        }
        try {
            AuthzMeResponse response = permissionService.get()
                    .uri("/api/v1/authz/me")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(AuthzMeResponse.class);
            Denial denial = denialReason(response);
            if (denial == null) return true;
            // An entitlement gap, not an incident: someone is missing a provisioning step.
            // Named so the next person does not have to reconstruct six steps from a 403.
            log.info("Etik Speak yetki reddi: {} userId={}", denial,
                    response == null ? "<yanit-yok>" : response.userId());
            return false;
        } catch (RuntimeException unavailableOrInvalid) {
            // Do not disclose dependency health or retain a previous allow — the caller still
            // gets the same denial. But this one is an outage and must not read as "no role".
            log.warn("Etik Speak yetki reddi: {} ({})", Denial.PERMISSION_SERVICE_UNAVAILABLE,
                    unavailableOrInvalid.getClass().getSimpleName());
            return false;
        }
    }

    /** Why access was refused, or {@code null} when it was not. */
    enum Denial {
        NO_BEARER,
        PERMISSION_SERVICE_UNAVAILABLE,
        EMPTY_RESPONSE,
        IDENTITY_INCOMPLETE,
        IDENTITY_MISMATCH,
        SUPER_ADMIN_NOT_ALLOWED,
        PROJECTION_INCOMPLETE,
        ROLE_MISSING,
        MODULE_NOT_MANAGE,
        MODULE_NOT_ALLOWED,
        PERMISSION_MISSING
    }

    /**
     * The first condition that failed, in the order they are checked, or {@code null}.
     *
     * <p>Returning the first rather than all of them is deliberate: the later checks read
     * fields the earlier ones proved present, so continuing past a failure would report
     * consequences as if they were causes.
     */
    static Denial denialReason(AuthzMeResponse response) {
        if (response == null) return Denial.EMPTY_RESPONSE;
        if (response.userId() == null || response.userId().isBlank() || response.subscriberId() == null)
            return Denial.IDENTITY_INCOMPLETE;
        if (!response.userId().equals(response.subscriberId().toString())) return Denial.IDENTITY_MISMATCH;
        if (!Boolean.FALSE.equals(response.superAdmin())) return Denial.SUPER_ADMIN_NOT_ALLOWED;
        if (response.roles() == null || response.modules() == null
                || response.allowedModules() == null || response.permissions() == null)
            return Denial.PROJECTION_INCOMPLETE;
        if (!response.roles().contains("ETIK_SPEAK_MANAGER")) return Denial.ROLE_MISSING;
        if (!"MANAGE".equals(response.modules().get("ETHIC"))) return Denial.MODULE_NOT_MANAGE;
        if (!response.allowedModules().contains("ETHIC")) return Denial.MODULE_NOT_ALLOWED;
        if (!response.permissions().contains("ETHIC")) return Denial.PERMISSION_MISSING;
        return null;
    }

    /**
     * Kept as the single boolean the rest of the service reads, and deliberately delegating:
     * two independent copies of this rule would drift, and the one that drifted would be the
     * one nobody was watching.
     */
    static boolean isExactEthicManage(AuthzMeResponse response) {
        return denialReason(response) == null;
    }

    record AuthzMeResponse(
            String userId,
            Long subscriberId,
            Boolean superAdmin,
            List<String> roles,
            Map<String, String> modules,
            List<String> allowedModules,
            List<String> permissions) {}
}
