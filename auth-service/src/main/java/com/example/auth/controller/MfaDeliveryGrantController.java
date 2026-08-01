package com.example.auth.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.auth.serviceauth.MfaDeliveryGrantIssuer;
import com.example.auth.serviceauth.MfaDeliveryGrantProperties;
import com.example.auth.serviceauth.ServiceClientsProperties;
import com.example.auth.serviceauth.ServiceClientsProperties.ClientRegistration;

/**
 * Issues one-shot MFA delivery grants (gitops#3212).
 *
 * <p>Why a separate endpoint and not extra claims on {@code /oauth2/token}:
 * a service token is a cached, reusable identity ("this caller may submit
 * notification intents"); a grant is a single transaction capability ("this
 * one code, to this one recipient, before this instant"). Merging them would
 * either poison the token cache or require a special uncached path that a
 * future refactor could quietly widen (Codex 019fb825).
 *
 * <p>What this endpoint does NOT do: it does not send anything, and it does
 * not decide whether the recipient is reachable. It attests that the caller
 * — an authenticated service client on the allow-list — asked to deliver one
 * MFA code to one recipient under one exact template/topic/channel. notify
 * verifies that attestation against the intent it receives and persists only
 * the derived evidence.
 */
@RestController
@RequestMapping("/oauth2")
public class MfaDeliveryGrantController {

    private final ServiceClientsProperties clientsProperties;
    private final MfaDeliveryGrantProperties props;
    private final MfaDeliveryGrantIssuer issuer;

    public MfaDeliveryGrantController(ServiceClientsProperties clientsProperties,
            MfaDeliveryGrantProperties props,
            MfaDeliveryGrantIssuer issuer) {
        this.clientsProperties = clientsProperties;
        this.props = props;
        this.issuer = issuer;
    }

    @PostMapping(value = "/mfa-delivery-grant",
            consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> grant(@RequestHeader Map<String, String> headers,
            @RequestBody MultiValueMap<String, String> form) {
        if (!props.isEnabled()) {
            // Fail-closed: with no allow-listed client the capability does not
            // exist at all, rather than existing in a permissive default.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "grant_disabled");
        }

        Credentials creds = resolveClientCredentials(headers, form);
        if (!authenticate(creds)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_client");
        }

        // Purpose first, then everything else against THAT purpose's lists
        // (gitops#3285). Callers that predate the parameter keep the MFA lane,
        // which is the only one they were ever able to ask for.
        String purpose = Optional.ofNullable(form.getFirst("purpose"))
                .filter(p -> !p.isBlank())
                .map(String::trim)
                .orElse(MfaDeliveryGrantProperties.PURPOSE_MFA_OTP);
        MfaDeliveryGrantProperties.Purpose policy = props.purpose(purpose);
        if (policy == null || !policy.isEnabled()) {
            // Unknown or unconfigured purposes are refused rather than
            // defaulted: defaulting would let a typo inherit another lane's
            // permissions, which is the whole thing this split prevents.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_purpose");
        }
        if (!policy.getAllowedClients().contains(creds.clientId())) {
            // Scoped to the purpose, so being trusted for one lane grants
            // nothing in another.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "client_not_allowed");
        }

        String audience = required(form, "audience");
        String subject = required(form, "subject");
        String recipient = required(form, "recipient");
        String channel = required(form, "channel");
        String topic = required(form, "topic");
        String template = required(form, "template");
        String authSessionId = required(form, "auth_session_id");

        ClientRegistration registration = clientsProperties.getClients().get(creds.clientId());
        if (registration == null || !registration.getAllowedAudiences().contains(audience)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_audience");
        }
        if (!policy.getAllowedChannels().contains(channel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_channel");
        }
        if (!policy.getAllowedTopics().contains(topic)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_topic");
        }
        if (!policy.getAllowedTemplates().contains(template)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_template");
        }
        // The recipient shape is pinned here as well as downstream: a grant
        // that could name a target this lane cannot deliver to would be a
        // grant for nothing. The shape follows the channel — pinning E.164
        // unconditionally would refuse every e-mail grant before it started.
        if (!recipientMatchesChannel(recipient, channel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_recipient");
        }

        String token = issuer.issue(new MfaDeliveryGrantIssuer.GrantRequest(
                creds.clientId(), audience, subject, recipient, channel, topic, template,
                authSessionId, purpose), Instant.now());

        return ResponseEntity.ok(Map.of(
                "grant", token,
                "expires_in", props.getTtlSeconds()));
    }

    private boolean authenticate(Credentials creds) {
        if (creds.clientId() == null || creds.clientId().isBlank()
                || creds.clientSecret() == null || creds.clientSecret().isBlank()) {
            return false;
        }
        ClientRegistration registration = clientsProperties.getClients().get(creds.clientId());
        if (registration == null || registration.getSecret() == null
                || registration.getSecret().isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                registration.getSecret().getBytes(StandardCharsets.UTF_8),
                creds.clientSecret().getBytes(StandardCharsets.UTF_8));
    }

    private static final java.util.regex.Pattern E164 =
            java.util.regex.Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /**
     * An unknown channel matches nothing: a channel that passed the allow-list
     * but has no shape here is a half-added channel, and issuing a grant for
     * it would be worse than refusing it.
     */
    private static boolean recipientMatchesChannel(String recipient, String channel) {
        return switch (channel) {
            case "sms" -> E164.matcher(recipient).matches();
            case "email" -> EMAIL.matcher(recipient).matches();
            default -> false;
        };
    }

    private String required(MultiValueMap<String, String> form, String key) {
        String value = form.getFirst(key);
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_" + key);
        }
        return value.trim();
    }

    private Credentials resolveClientCredentials(Map<String, String> headers,
            MultiValueMap<String, String> form) {
        String auth = headers.entrySet().stream()
                .filter(e -> "authorization".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
        if (auth != null && auth.toLowerCase(Locale.ROOT).startsWith("basic ")) {
            try {
                String decoded = new String(
                        Base64.getDecoder().decode(auth.substring(6).trim()), StandardCharsets.UTF_8);
                int idx = decoded.indexOf(':');
                if (idx > 0) {
                    return new Credentials(decoded.substring(0, idx), decoded.substring(idx + 1));
                }
            } catch (IllegalArgumentException ignored) {
                return new Credentials(null, null);
            }
        }
        return new Credentials(
                Optional.ofNullable(form.getFirst("client_id")).orElse(null),
                Optional.ofNullable(form.getFirst("client_secret")).orElse(null));
    }

    private record Credentials(String clientId, String clientSecret) {}
}
