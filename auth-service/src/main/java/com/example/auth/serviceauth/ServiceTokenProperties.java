package com.example.auth.serviceauth;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "dev"})
@ConfigurationProperties(prefix = "security.service-token")
public class ServiceTokenProperties {

    /**
     * Token'ın audience claim değeri.
     */
    private String audience = "permission-service";

    /**
     * Token geçerlilik süresi (saniye).
     */
    private long ttlSeconds = 60;

    /**
     * Token içerisinde gönderilecek izin listesi.
     */
    private List<String> permissions = List.of("permissions:read");

    /**
     * Ortam bilgisi (env claim).
     */
    private String environment = "local";

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }
}
