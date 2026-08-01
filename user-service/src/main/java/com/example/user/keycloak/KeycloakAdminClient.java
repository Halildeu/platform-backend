package com.example.user.keycloak;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Narrow Keycloak admin REST surface for the panel MFA section
 * (gitops#3211): read MFA state, delete the OTP credential (reset), and
 * get/set the phone attribute used by the SMS OTP lane (gitops#3212).
 *
 * <p>Authenticates with KC client-credentials on the dedicated
 * {@code user-mfa-admin} confidential client (service account holding only
 * realm-management view-users + manage-users). Token cached until shortly
 * before expiry. Shape follows PendingActivationNotificationClient /
 * UserAuditMirrorClient (typed properties + injected builder + per-call
 * block with an explicit budget).
 */
@Component
public class KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);
    private static final Duration EXPIRY_SAFETY = Duration.ofSeconds(30);

    private final KeycloakAdminApiProperties props;
    private final WebClient webClient;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    public KeycloakAdminClient(KeycloakAdminApiProperties props,
            @Qualifier("directWebClientBuilder") WebClient.Builder directWebClientBuilder) {
        this.props = props;
        this.webClient = directWebClientBuilder.baseUrl(props.getBaseUrl()).build();
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** Snapshot of everything the panel MFA section shows for one KC user. */
    public record MfaSnapshot(String kcUserId, String username, boolean requiresMfa,
            boolean totpConfigured, String phoneNumber, List<String> allowedMethods) {}

    /**
     * Keycloak user id → login name, for the whole realm (gitops#3291).
     * <p>
     * {@code briefRepresentation=true} keeps the payload to identity fields, so
     * one call covers a page of the realm rather than one call per user — the
     * admin API has no get-by-ids, and a per-row lookup would make the users
     * grid pay a round trip for every row it renders.
     * <p>
     * Pages until Keycloak returns a short page, capped so a misconfigured
     * realm cannot spin here forever.
     */
    public Map<String, String> listUsernames() {
        Map<String, String> out = new LinkedHashMap<>();
        int page = props.getUsernameSyncPageSize();
        for (int first = 0; first < MAX_USERNAME_SYNC_USERS; first += page) {
            final int offset = first;
            JsonNode batch = adminRequest(spec -> spec.get()
                    .uri(b -> b.path("/admin/realms/{realm}/users")
                            .queryParam("first", offset)
                            .queryParam("max", page)
                            .queryParam("briefRepresentation", "true")
                            .build(props.getRealm()))
                    .retrieve().bodyToMono(JsonNode.class));
            if (batch == null || !batch.isArray() || batch.isEmpty()) {
                break;
            }
            batch.forEach(u -> {
                String id = u.path("id").asText(null);
                String username = u.path("username").asText(null);
                if (id != null && username != null) {
                    out.put(id, username);
                }
            });
            if (batch.size() < page) {
                break;
            }
        }
        return out;
    }

    /** Backstop against an unbounded paging loop; far above any real realm. */
    private static final int MAX_USERNAME_SYNC_USERS = 10_000;

    public Optional<MfaSnapshot> fetchMfaSnapshot(String kcSubject, String canonicalEmail) {
        JsonNode user = resolveUser(kcSubject, canonicalEmail);
        if (user == null) {
            return Optional.empty();
        }
        String kcUserId = user.path("id").asText();
        String phone = user.path("attributes").path(props.getPhoneAttribute()).path(0).asText(null);

        boolean totp = credentials(kcUserId).stream()
                .anyMatch(c -> "otp".equals(c.path("type").asText()));

        boolean requiresMfa = realmRoles(kcUserId).stream()
                .anyMatch(r -> props.getRequiresMfaRole().equals(r.path("name").asText()));

        // Empty list means "no restriction" all the way through: the SPI reads
        // an absent attribute the same way, so the two ends agree without a
        // second convention to keep in sync.
        List<String> methods = new ArrayList<>();
        JsonNode raw = user.path("attributes").path(props.getMethodsAttribute());
        if (raw.isArray()) {
            raw.forEach(v -> {
                for (String part : v.asText("").split(",")) {
                    String trimmed = part.trim().toLowerCase(java.util.Locale.ROOT);
                    if (!trimmed.isEmpty()) {
                        methods.add(trimmed);
                    }
                }
            });
        }

        return Optional.of(new MfaSnapshot(kcUserId, user.path("username").asText(null),
                requiresMfa, totp, phone, methods));
    }

    /** Delete every OTP credential (reset; next login re-enrolls if required). */
    public int deleteOtpCredentials(String kcUserId) {
        int deleted = 0;
        for (JsonNode cred : credentials(kcUserId)) {
            if (!"otp".equals(cred.path("type").asText())) {
                continue;
            }
            String credId = cred.path("id").asText();
            adminRequest(spec -> spec.method(org.springframework.http.HttpMethod.DELETE)
                    .uri("/admin/realms/{realm}/users/{id}/credentials/{cred}",
                            props.getRealm(), kcUserId, credId)
                    .retrieve().toBodilessEntity());
            deleted++;
        }
        log.info("mfa-admin: deleted {} otp credential(s) for kc user {}", deleted, kcUserId);
        return deleted;
    }

    /**
     * Set (E.164) or clear (null) the phone attribute. GET-then-PUT of the
     * full representation: KC's user update replaces the attribute map, so a
     * blind PUT with only the phone key would wipe every other attribute.
     */
    public void setPhoneAttribute(String kcUserId, String phoneOrNull) {
        JsonNode user = adminRequest(spec -> spec.get()
                .uri("/admin/realms/{realm}/users/{id}", props.getRealm(), kcUserId)
                .retrieve().bodyToMono(JsonNode.class));
        Map<String, Object> representation =
                mapper.convertValue(user, new com.fasterxml.jackson.core.type.TypeReference<>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = representation.get("attributes") instanceof Map<?, ?> m
                ? new HashMap<>((Map<String, Object>) m)
                : new HashMap<>();
        if (phoneOrNull == null || phoneOrNull.isBlank()) {
            attributes.remove(props.getPhoneAttribute());
        } else {
            attributes.put(props.getPhoneAttribute(), List.of(phoneOrNull));
        }
        representation.put("attributes", attributes);

        adminRequest(spec -> spec.put()
                .uri("/admin/realms/{realm}/users/{id}", props.getRealm(), kcUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(representation)
                .retrieve().toBodilessEntity());
        log.info("mfa-admin: phone attribute {} for kc user {}",
                phoneOrNull == null || phoneOrNull.isBlank() ? "cleared" : "set", kcUserId);
    }

    /** Raised when the realm has no role to hang the requirement on. */
    public static class RequiresMfaRoleMissingException extends RuntimeException {
        public RequiresMfaRoleMissingException(String message) {
            super(message);
        }
    }

    /**
     * Turn the second-factor requirement on or off for one user.
     *
     * <p>The requirement is not a flag on the account: the privileged browser
     * flow gates on a {@code Condition - user role} whose config names the
     * realm role in {@link KeycloakAdminApiProperties#getRequiresMfaRole()}.
     * Toggling it is therefore assigning or removing that role.
     *
     * <p>The role representation is read from the <em>user-scoped</em>
     * assigned/available lists rather than from {@code /roles/{name}},
     * because the narrow service account is deliberately not granted
     * view-realm — measured on the live client:
     * <pre>
     *   GET /roles/requires-mfa                        403
     *   GET /users/{id}/role-mappings/realm            200
     *   GET /users/{id}/role-mappings/realm/available  200
     * </pre>
     * Going through the user-scoped lists keeps the grant at view-users +
     * manage-users.
     *
     * <p>Idempotent: the desired state is compared against the assigned list
     * first, so toggling twice is a no-op rather than an error.
     *
     * @return true when a change was actually written
     */
    public boolean setRequiresMfa(String kcUserId, boolean required) {
        String roleName = props.getRequiresMfaRole();
        JsonNode assigned = findRole(realmRoles(kcUserId), roleName);

        if (required == (assigned != null)) {
            log.debug("mfa-admin: requires-mfa already {} for kc user {}",
                    required ? "on" : "off", kcUserId);
            return false;
        }

        JsonNode role = assigned != null ? assigned : findRole(availableRealmRoles(kcUserId), roleName);
        if (role == null) {
            // Neither assigned nor assignable: the realm does not carry the
            // role at all, so the requirement cannot be expressed. Saying so
            // beats silently reporting success for a setting that will never
            // take effect.
            throw new RequiresMfaRoleMissingException(
                    "realm role '" + roleName + "' not found; the privileged MFA flow gates on it");
        }

        List<Map<String, String>> body = List.of(Map.of(
                "id", role.path("id").asText(),
                "name", role.path("name").asText()));

        adminRequest(spec -> spec.method(required
                        ? org.springframework.http.HttpMethod.POST
                        : org.springframework.http.HttpMethod.DELETE)
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm",
                        props.getRealm(), kcUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().toBodilessEntity());

        log.info("mfa-admin: requires-mfa {} for kc user {}",
                required ? "granted" : "revoked", kcUserId);
        return true;
    }

    /**
     * Set (non-empty) or clear (empty/null) the per-user method allow-list.
     *
     * <p>Same GET-then-PUT merge as the phone attribute, and for the same
     * reason: Keycloak's user update replaces the whole attribute map, so a
     * blind PUT would take every other attribute with it.
     */
    public void setAllowedMethods(String kcUserId, List<String> methodsOrEmpty) {
        JsonNode user = adminRequest(spec -> spec.get()
                .uri("/admin/realms/{realm}/users/{id}", props.getRealm(), kcUserId)
                .retrieve().bodyToMono(JsonNode.class));
        Map<String, Object> representation =
                mapper.convertValue(user, new com.fasterxml.jackson.core.type.TypeReference<>() {});

        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = representation.get("attributes") instanceof Map<?, ?> m
                ? new HashMap<>((Map<String, Object>) m)
                : new HashMap<>();

        List<String> clean = methodsOrEmpty == null ? List.of() : methodsOrEmpty.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .toList();

        if (clean.isEmpty()) {
            // Removing the attribute rather than storing an empty list: both
            // read as "no restriction", and one of them is not a value that
            // has to be explained later.
            attributes.remove(props.getMethodsAttribute());
        } else {
            attributes.put(props.getMethodsAttribute(), clean);
        }
        representation.put("attributes", attributes);

        adminRequest(spec -> spec.put()
                .uri("/admin/realms/{realm}/users/{id}", props.getRealm(), kcUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(representation)
                .retrieve().toBodilessEntity());
        log.info("mfa-admin: allowed methods {} for kc user {}",
                clean.isEmpty() ? "cleared" : clean, kcUserId);
    }

    // ── internals ──

    private JsonNode resolveUser(String kcSubject, String canonicalEmail) {
        if (kcSubject != null && !kcSubject.isBlank()) {
            try {
                return adminRequest(spec -> spec.get()
                        .uri("/admin/realms/{realm}/users/{id}", props.getRealm(), kcSubject)
                        .retrieve().bodyToMono(JsonNode.class));
            } catch (WebClientResponseException.NotFound e) {
                log.warn("mfa-admin: kc_subject {} not found in realm, falling back to email",
                        kcSubject);
            }
        }
        if (canonicalEmail == null || canonicalEmail.isBlank()) {
            return null;
        }
        JsonNode matches = adminRequest(spec -> spec.get()
                .uri(b -> b.path("/admin/realms/{realm}/users")
                        .queryParam("email", canonicalEmail)
                        .queryParam("exact", "true")
                        .build(props.getRealm()))
                .retrieve().bodyToMono(JsonNode.class));
        return matches != null && matches.isArray() && matches.size() > 0 ? matches.get(0) : null;
    }

    private List<JsonNode> credentials(String kcUserId) {
        JsonNode array = adminRequest(spec -> spec.get()
                .uri("/admin/realms/{realm}/users/{id}/credentials", props.getRealm(), kcUserId)
                .retrieve().bodyToMono(JsonNode.class));
        List<JsonNode> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(out::add);
        }
        return out;
    }

    private static JsonNode findRole(List<JsonNode> roles, String name) {
        return roles.stream()
                .filter(r -> name.equals(r.path("name").asText()))
                .findFirst()
                .orElse(null);
    }

    private List<JsonNode> availableRealmRoles(String kcUserId) {
        JsonNode array = adminRequest(spec -> spec.get()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm/available",
                        props.getRealm(), kcUserId)
                .retrieve().bodyToMono(JsonNode.class));
        List<JsonNode> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(out::add);
        }
        return out;
    }

    private List<JsonNode> realmRoles(String kcUserId) {
        JsonNode array = adminRequest(spec -> spec.get()
                .uri("/admin/realms/{realm}/users/{id}/role-mappings/realm",
                        props.getRealm(), kcUserId)
                .retrieve().bodyToMono(JsonNode.class));
        List<JsonNode> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(out::add);
        }
        return out;
    }

    private <T> T adminRequest(java.util.function.Function<WebClient, reactor.core.publisher.Mono<T>> call) {
        String token = adminToken();
        WebClient authed = webClient.mutate()
                .defaultHeaders(h -> h.setBearerAuth(token))
                .build();
        return call.apply(authed).block(Duration.ofMillis(props.getTimeoutMillis()));
    }

    private synchronized String adminToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return cachedToken;
        }
        JsonNode response = webClient.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", props.getRealm())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(h -> h.setBasicAuth(props.getClientId(), props.getClientSecret()))
                .body(BodyInserters.fromFormData("grant_type", "client_credentials"))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofMillis(props.getTimeoutMillis()));
        if (response == null || response.path("access_token").asText("").isBlank()) {
            throw new IllegalStateException("keycloak admin token mint returned no access_token");
        }
        cachedToken = response.path("access_token").asText();
        long expiresIn = response.path("expires_in").asLong(60);
        cachedTokenExpiry = Instant.now().plusSeconds(expiresIn).minus(EXPIRY_SAFETY);
        return cachedToken;
    }
}
