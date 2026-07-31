package com.example.auth.serviceauth;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Policy for the MFA delivery grant (gitops#3212).
 *
 * <p>The grant is a separate, very short-lived capability — deliberately NOT
 * extra claims on the service access token. A service token says "this caller
 * may submit notification intents"; the grant says "this ONE delivery, to this
 * recipient, for this template, before this instant". Mixing them would turn a
 * cached, reusable identity into a transaction capability (Codex 019fb825).
 */
@Component
@ConfigurationProperties(prefix = "security.mfa-delivery-grant")
public class MfaDeliveryGrantProperties {

    /** Service clients allowed to request a grant. Empty = feature disabled. */
    private Set<String> allowedClients = Set.of();

    /** Topics a grant may authorise. */
    private Set<String> allowedTopics = Set.of("auth.mfa.sms-otp");

    /** Templates a grant may authorise. */
    private Set<String> allowedTemplates = Set.of("auth.sms-otp");

    /** Channels a grant may authorise. */
    private Set<String> allowedChannels = Set.of("sms");

    /**
     * Grant lifetime. Short on purpose: it only has to survive the hop from
     * Keycloak to the intent submit, not the asynchronous delivery that
     * follows (the verified evidence is persisted at submit time).
     */
    private int ttlSeconds = 120;

    /**
     * How long after issuance the delivery itself stays authorised. The
     * dispatch worker runs asynchronously, so this is the window it checks —
     * separate from the grant's own exp, which governs the submit hop.
     */
    private int deliverWithinSeconds = 600;

    public Set<String> getAllowedClients() { return allowedClients; }
    public void setAllowedClients(Set<String> allowedClients) { this.allowedClients = allowedClients; }
    public Set<String> getAllowedTopics() { return allowedTopics; }
    public void setAllowedTopics(Set<String> allowedTopics) { this.allowedTopics = allowedTopics; }
    public Set<String> getAllowedTemplates() { return allowedTemplates; }
    public void setAllowedTemplates(Set<String> allowedTemplates) { this.allowedTemplates = allowedTemplates; }
    public Set<String> getAllowedChannels() { return allowedChannels; }
    public void setAllowedChannels(Set<String> allowedChannels) { this.allowedChannels = allowedChannels; }
    public int getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(int ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    public int getDeliverWithinSeconds() { return deliverWithinSeconds; }
    public void setDeliverWithinSeconds(int deliverWithinSeconds) { this.deliverWithinSeconds = deliverWithinSeconds; }

    public boolean isEnabled() {
        return !allowedClients.isEmpty();
    }
}
