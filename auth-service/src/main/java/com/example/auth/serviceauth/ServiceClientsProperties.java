package com.example.auth.serviceauth;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "dev"})
@ConfigurationProperties(prefix = "security.service-clients")
public class ServiceClientsProperties {
    /**
     * İzin verilen servis istemcileri (clientId -> clientSecret)
     * Örnek: security.service-clients.user-service=dev-secret
     */
    private Map<String, String> clients = new HashMap<>();

    public Map<String, String> getClients() {
        return clients;
    }

    public void setClients(Map<String, String> clients) {
        this.clients = clients;
    }
}
