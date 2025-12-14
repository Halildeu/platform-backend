package com.example.user.serviceauth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "security.service-auth")
public class ServiceTokenVerificationProperties {

    /**
     * Beklenen audience (aud claim). Varsayılan servis adı.
     */
    private String expectedAudience = "user-service";

    /**
     * JWKS endpoint URL.
     */
    private String jwkSetUri = "http://auth-service:8088/oauth2/jwks";

    /**
     * Beklenen token issuer.
     */
    private String issuer = "auth-service";

    /**
     * Beklenen environment değeri (env claim). Opsiyonel.
     */
    private String expectedEnvironment;

    public String getExpectedAudience() {
        return expectedAudience;
    }

    public void setExpectedAudience(String expectedAudience) {
        this.expectedAudience = expectedAudience;
    }

    public String getExpectedEnvironment() {
        return expectedEnvironment;
    }

    public void setExpectedEnvironment(String expectedEnvironment) {
        this.expectedEnvironment = expectedEnvironment;
    }

    public String getJwkSetUri() {
        return jwkSetUri;
    }

    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }
}
