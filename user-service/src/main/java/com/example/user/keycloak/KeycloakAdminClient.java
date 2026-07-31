package com.example.user.keycloak;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
    public record MfaSnapshot(String kcUserId, boolean requiresMfa, boolean totpConfigured,
            String phoneNumber) {}

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

        return Optional.of(new MfaSnapshot(kcUserId, requiresMfa, totp, phone));
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
