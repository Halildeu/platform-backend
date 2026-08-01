package com.example.auth.serviceauth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Policy for delivery grants (gitops#3212, extended for a second purpose in
 * gitops#3285).
 *
 * <p>A grant is a separate, very short-lived capability — deliberately NOT
 * extra claims on the service access token. A service token says "this caller
 * may submit notification intents"; the grant says "this ONE delivery, to this
 * recipient, for this template, before this instant". Mixing them would turn a
 * cached, reusable identity into a transaction capability (Codex 019fb825).
 *
 * <h2>Why the allow-lists live inside a purpose</h2>
 *
 * They used to be four flat sets, which was correct while there was exactly one
 * purpose. Adding {@code account_invite} to flat sets would have been a hole
 * rather than a feature: the lists would no longer say WHICH purpose may use
 * what, so user-service — added so it can send invitations — could just as well
 * mint an MFA OTP grant, and the Keycloak SPI could mint an invitation. Cross-use
 * would be prevented by convention instead of by construction.
 *
 * <p>With a bundle per purpose the issuer validates against that purpose's lists
 * and no other, so a caller absent from a purpose's client list simply cannot
 * obtain that kind of grant. {@code mfa_otp} keeps exactly the values it had.
 *
 * <p>The prefix still says {@code mfa-delivery-grant}. Renaming it is a
 * deliberate non-goal here: the only live override is
 * {@code SECURITY_MFA_DELIVERY_GRANT_ALLOWED_CLIENTS}, so a rename needs the
 * config and the image to land in the same apply, and it buys naming tidiness
 * rather than behaviour. The class-level names are accurate; the key is the one
 * stale label, and it is recorded here rather than left to be discovered.
 */
@Component
@ConfigurationProperties(prefix = "security.mfa-delivery-grant")
public class MfaDeliveryGrantProperties {

    /** The MFA one-time-code lane (gitops#3212). */
    public static final String PURPOSE_MFA_OTP = "mfa_otp";

    /** The account-invitation lane (gitops#3285). */
    public static final String PURPOSE_ACCOUNT_INVITE = "account_invite";

    /** What one purpose may authorise. Every list is exact; nothing is implied. */
    public static class Purpose {
        private Set<String> allowedClients = Set.of();
        private Set<String> allowedTopics = Set.of();
        private Set<String> allowedTemplates = Set.of();
        private Set<String> allowedChannels = Set.of();

        public Set<String> getAllowedClients() { return allowedClients; }
        public void setAllowedClients(Set<String> v) { this.allowedClients = v; }
        public Set<String> getAllowedTopics() { return allowedTopics; }
        public void setAllowedTopics(Set<String> v) { this.allowedTopics = v; }
        public Set<String> getAllowedTemplates() { return allowedTemplates; }
        public void setAllowedTemplates(Set<String> v) { this.allowedTemplates = v; }
        public Set<String> getAllowedChannels() { return allowedChannels; }
        public void setAllowedChannels(Set<String> v) { this.allowedChannels = v; }

        /** A purpose with no client may authorise nothing — fail-closed. */
        public boolean isEnabled() {
            return !allowedClients.isEmpty();
        }
    }

    /**
     * Legacy flat client list. This is the ONE key set from the overlay
     * ({@code SECURITY_MFA_DELIVERY_GRANT_ALLOWED_CLIENTS}), so it keeps
     * feeding {@code mfa_otp} rather than forcing the config and the image to
     * flip in the same apply. A purpose-scoped list, when present, wins.
     */
    private Set<String> allowedClients = Set.of();

    /**
     * Bound from configuration. Only the fields an operator actually sets
     * arrive here — Spring replaces the whole {@link Purpose} for a key it
     * binds, so anything pre-populated in a constructor would be silently
     * dropped. The shape below therefore keeps configuration and defaults in
     * separate maps and merges them at read time.
     */
    private final Map<String, Purpose> purposes = new LinkedHashMap<>();

    /**
     * The lanes that exist, and what each may address. Defined in code, not
     * configuration: a purpose is a security boundary, and inventing one should
     * take a reviewed change rather than a property. Configuration decides WHO
     * may use a lane; code decides WHICH lanes there are and what they cover.
     */
    private static final Map<String, Purpose> DEFAULTS = defaults();

    private static Map<String, Purpose> defaults() {
        Map<String, Purpose> m = new LinkedHashMap<>();

        Purpose mfa = new Purpose();
        mfa.setAllowedTopics(Set.of("auth.mfa.sms-otp", "auth.mfa.email-otp"));
        mfa.setAllowedTemplates(Set.of("auth.sms-otp", "auth.email-otp"));
        mfa.setAllowedChannels(Set.of("sms", "email"));
        m.put(PURPOSE_MFA_OTP, mfa);

        Purpose invite = new Purpose();
        invite.setAllowedTopics(Set.of("auth.admin-invite"));
        invite.setAllowedTemplates(Set.of("auth.admin-invite"));
        // E-mail only: an invitation goes to an address someone typed, and
        // there is no phone number for an account that does not exist yet.
        invite.setAllowedChannels(Set.of("email"));
        m.put(PURPOSE_ACCOUNT_INVITE, invite);

        return Map.copyOf(m);
    }

    /**
     * Grant lifetime. Short on purpose: it only has to survive the hop from the
     * caller to the intent submit, not the asynchronous delivery that follows
     * (the verified evidence is persisted at submit time).
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
    public Map<String, Purpose> getPurposes() { return purposes; }
    public int getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(int ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    public int getDeliverWithinSeconds() { return deliverWithinSeconds; }
    public void setDeliverWithinSeconds(int deliverWithinSeconds) { this.deliverWithinSeconds = deliverWithinSeconds; }

    /**
     * The effective policy for one purpose, or {@code null} if the purpose is
     * unknown. Unknown is refused rather than defaulted — defaulting would let
     * a typo inherit another lane's permissions.
     *
     * <p>Configuration overrides the built-in lists field by field; an unset
     * field keeps its default rather than becoming empty, so setting only
     * {@code allowed-clients} does not accidentally strip a lane of every topic
     * it can address.
     */
    public Purpose purpose(String name) {
        Purpose base = DEFAULTS.get(name);
        if (base == null) {
            return null;
        }
        Purpose configured = purposes.get(name);
        Purpose out = new Purpose();

        Set<String> clients = configured == null ? Set.of() : configured.getAllowedClients();
        if (clients.isEmpty() && PURPOSE_MFA_OTP.equals(name)) {
            // Legacy fallback, and ONLY for the lane the flat key was written
            // for. Applying it to every purpose would hand the MFA client the
            // invitation lane for free — the exact widening this design refuses.
            clients = allowedClients;
        }
        out.setAllowedClients(clients);
        out.setAllowedTopics(pick(configured == null ? null : configured.getAllowedTopics(),
                base.getAllowedTopics()));
        out.setAllowedTemplates(pick(configured == null ? null : configured.getAllowedTemplates(),
                base.getAllowedTemplates()));
        out.setAllowedChannels(pick(configured == null ? null : configured.getAllowedChannels(),
                base.getAllowedChannels()));
        return out;
    }

    private static Set<String> pick(Set<String> configured, Set<String> fallback) {
        return configured == null || configured.isEmpty() ? fallback : configured;
    }

    /** True when at least one lane has somebody who may use it. */
    public boolean isEnabled() {
        return !allowedClients.isEmpty()
                || DEFAULTS.keySet().stream().anyMatch(n -> purpose(n).isEnabled());
    }
}
