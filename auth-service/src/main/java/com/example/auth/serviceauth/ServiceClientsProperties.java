package com.example.auth.serviceauth;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "security.service-clients")
public class ServiceClientsProperties {
    /**
     * Registered OAuth2 client-credentials principals, keyed by client id.
     *
     * <p>The explicit {@code clients} level is intentional. It gives Spring Boot a stable
     * map binding shape and keeps the secret next to the client-specific audience and
     * permission ceilings:
     * {@code security.service-clients.clients.meeting-ai.secret=...}.
     */
    private Map<String, ClientRegistration> clients = new HashMap<>();

    public Map<String, ClientRegistration> getClients() {
        return clients;
    }

    public void setClients(Map<String, ClientRegistration> clients) {
        this.clients = clients == null ? new HashMap<>() : clients;
    }

    public static class ClientRegistration {
        private String secret = "";
        private Set<String> allowedAudiences = new LinkedHashSet<>();
        private Set<String> allowedPermissions = new LinkedHashSet<>();
        private boolean requireExplicitPermissions = true;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public Set<String> getAllowedAudiences() {
            return allowedAudiences;
        }

        public void setAllowedAudiences(Set<String> allowedAudiences) {
            this.allowedAudiences = allowedAudiences == null
                    ? new LinkedHashSet<>()
                    : allowedAudiences;
        }

        public Set<String> getAllowedPermissions() {
            return allowedPermissions;
        }

        public void setAllowedPermissions(Set<String> allowedPermissions) {
            this.allowedPermissions = allowedPermissions == null
                    ? new LinkedHashSet<>()
                    : allowedPermissions;
        }

        public boolean isRequireExplicitPermissions() {
            return requireExplicitPermissions;
        }

        public void setRequireExplicitPermissions(boolean requireExplicitPermissions) {
            this.requireExplicitPermissions = requireExplicitPermissions;
        }
    }
}
