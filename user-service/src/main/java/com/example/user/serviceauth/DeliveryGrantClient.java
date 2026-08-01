package com.example.user.serviceauth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Obtains a one-shot delivery grant from auth-service (gitops#3285).
 *
 * <p>notify refuses an external recipient without a `can_receive` relation,
 * and an invited address has none by construction — the person does not exist
 * in the system yet. The grant is the answer that already exists: auth-service
 * attests "this ONE delivery, to this recipient, for this template, before this
 * instant", and notify verifies that attestation against the intent it receives.
 *
 * <p>Deliberately NOT cached, unlike the service token next door. A token is a
 * reusable identity; a grant is a single transaction capability, and caching one
 * would turn it into the other. Every invitation mints its own.
 *
 * <p>The purpose is fixed to {@code account_invite} here rather than passed in.
 * user-service is on that lane's client list and no other, so a wider parameter
 * would only offer a future caller a way to ask for something auth-service will
 * refuse anyway — better that the narrow truth is visible at the call site.
 */
@Component
public class DeliveryGrantClient {

    private static final Logger log = LoggerFactory.getLogger(DeliveryGrantClient.class);

    /** The only lane this service is allow-listed for. */
    public static final String PURPOSE_ACCOUNT_INVITE = "account_invite";

    private final ServiceTokenClientProperties clientProperties;
    private final WebClient webClient;

    public DeliveryGrantClient(ServiceTokenClientProperties clientProperties,
                               WebClient.Builder webClientBuilder) {
        this.clientProperties = clientProperties;
        this.webClient = webClientBuilder.build();
    }

    /**
     * @return the signed grant, or {@code null} when one could not be obtained.
     *         Null is a caller decision point, not a failure to hide: submitting
     *         without a grant simply falls back to the ordinary authz path,
     *         which is where the intent was being refused in the first place.
     */
    public String mintForInvite(String audience, String subject, String recipient,
                                String topic, String template) {
        if (!clientProperties.isEnabled()) {
            return null;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("purpose", PURPOSE_ACCOUNT_INVITE);
        form.add("audience", audience);
        form.add("subject", subject);
        form.add("recipient", recipient);
        form.add("channel", "email");
        form.add("topic", topic);
        form.add("template", template);
        // The grant schema carries an auth-session id because it was built for a
        // login challenge. An invitation has no login session, so the value
        // names the thing that IS being authorised — the account being invited.
        form.add("auth_session_id", "invite:" + subject);

        String basic = Base64.getEncoder().encodeToString(
                (clientProperties.getClientId() + ":" + clientProperties.getClientSecret())
                        .getBytes(StandardCharsets.UTF_8));

        try {
            Map<?, ?> body = webClient.post()
                    .uri(grantUrl())
                    .headers(h -> {
                        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                        h.set(HttpHeaders.AUTHORIZATION, "Basic " + basic);
                    })
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(Duration.ofSeconds(5));
            Object grant = body == null ? null : body.get("grant");
            if (grant instanceof String s && !s.isBlank()) {
                return s;
            }
            log.warn("invite delivery grant: response carried no grant");
            return null;
        } catch (RuntimeException ex) {
            // Never the raw exception body: a failed mint can echo request
            // details, and this path handles someone's e-mail address.
            log.warn("invite delivery grant refused or unreachable: {}", ex.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Derived from the token URL rather than configured separately: the two
     * endpoints live on the same auth-service and a second property would be
     * one more thing to get out of step. A token URL that does not end in the
     * expected path is left alone, so an unusual deployment degrades to a
     * refused mint rather than a request aimed at the wrong place.
     */
    private String grantUrl() {
        String tokenUrl = clientProperties.getTokenUrl();
        return tokenUrl.endsWith("/oauth2/token")
                ? tokenUrl.substring(0, tokenUrl.length() - "/token".length()) + "/mfa-delivery-grant"
                : tokenUrl;
    }
}
