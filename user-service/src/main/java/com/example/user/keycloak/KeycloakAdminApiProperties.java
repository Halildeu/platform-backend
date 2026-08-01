package com.example.user.keycloak;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Keycloak admin REST access for the narrow MFA management surface
 * (gitops#3211). Deliberately NOT the master admin: the expected client is a
 * dedicated confidential client (default {@code user-mfa-admin}) whose
 * service account carries only realm-management {@code view-users} +
 * {@code manage-users} — provisioned by
 * {@code scripts/keycloak/setup-user-mfa-admin-client.sh} in
 * platform-k8s-gitops. A blank secret keeps the whole surface disabled
 * (503), never half-working.
 */
@Component
@ConfigurationProperties(prefix = "keycloak.admin-api")
public class KeycloakAdminApiProperties {

    /** KC base URL as reachable from the cluster (Service+Endpoints pin). */
    private String baseUrl = "http://keycloak:8080";

    /** Realm whose users this panel manages. */
    private String realm = "platform-test";

    private String clientId = "user-mfa-admin";

    /** ESO-fed; blank disables the MFA admin surface fail-closed. */
    private String clientSecret = "";

    private int timeoutMillis = 5000;

    /** User attribute carrying the E.164 phone for the SMS OTP lane. */
    private String phoneAttribute = "phoneNumber";

    /** Realm role marking accounts that must present a second factor. */
    private String requiresMfaRole = "requires-mfa";

    /** User attribute holding the per-user second-factor allow-list. */
    private String methodsAttribute = "mfaMethods";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getRealm() { return realm; }
    public void setRealm(String realm) { this.realm = realm; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public int getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(int timeoutMillis) { this.timeoutMillis = timeoutMillis; }
    public String getPhoneAttribute() { return phoneAttribute; }
    public void setPhoneAttribute(String phoneAttribute) { this.phoneAttribute = phoneAttribute; }
    public String getMethodsAttribute() { return methodsAttribute; }
    public void setMethodsAttribute(String methodsAttribute) { this.methodsAttribute = methodsAttribute; }
    public String getRequiresMfaRole() { return requiresMfaRole; }
    public void setRequiresMfaRole(String requiresMfaRole) { this.requiresMfaRole = requiresMfaRole; }

    public boolean isEnabled() {
        return clientSecret != null && !clientSecret.isBlank();
    }
}
