package com.example.kcsmsotp;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Factory for the SMS OTP authenticator (provider id {@code sms-otp}).
 * Registered via META-INF/services; deployed as a providers/ JAR into the
 * Keycloak compose container (platform-k8s-gitops host-compose/keycloak).
 */
public class SmsOtpAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "sms-otp";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RNG = new SecureRandom();

    private static final SmsOtpAuthenticator SINGLETON =
            new SmsOtpAuthenticator(HTTP, MAPPER, RNG, Clock.systemUTC());

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.ALTERNATIVE,
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.DISABLED,
    };

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return SINGLETON;
    }

    @Override
    public String getDisplayType() {
        return "SMS OTP (notify pipeline)";
    }

    @Override
    public String getReferenceCategory() {
        return "sms-otp";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        // Phone enrollment happens in the admin panel (gitops#3211), not via a
        // Keycloak required action, so there is nothing for KC to set up.
        return false;
    }

    @Override
    public String getHelpText() {
        return "Sends a one-time code to the user's phone attribute through the platform "
                + "notify pipeline (auth-service service-token mint -> internal intent -> SMS "
                + "provider) and verifies it. Client secret comes from the "
                + SmsOtpConfig.SECRET_ENV + " container env, never from this config.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of(
                property(SmsOtpConfig.CFG_TOKEN_URL, "Auth token URL",
                        "auth-service client-credentials endpoint, e.g. http://k3d-test-server-0:31088/oauth2/token"),
                property(SmsOtpConfig.CFG_INTENT_URL, "Notify intent URL",
                        "notification-orchestrator internal submit endpoint, e.g. "
                                + "http://k3d-test-server-0:31089/api/v1/internal/notify/intents"),
                property(SmsOtpConfig.CFG_CLIENT_ID, "Service client id",
                        "auth-service service-clients registration name (default keycloak-sms-otp)"),
                property(SmsOtpConfig.CFG_ORG_ID, "Org id",
                        "Intent partition selector (default platform-system)"),
                property(SmsOtpConfig.CFG_TOPIC_KEY, "Topic key",
                        "Intent topic (default auth.mfa.sms-otp)"),
                property(SmsOtpConfig.CFG_TEMPLATE_ID, "Template id",
                        "notification_template id (default auth.sms-otp)"),
                property(SmsOtpConfig.CFG_TTL_SECONDS, "Code TTL seconds", "default 300"),
                property(SmsOtpConfig.CFG_MAX_ATTEMPTS, "Max verify attempts", "default 3"),
                property(SmsOtpConfig.CFG_MAX_RESENDS, "Max resends", "default 2"),
                property(SmsOtpConfig.CFG_PHONE_ATTRIBUTE, "Phone attribute",
                        "User attribute holding the E.164 number (default phoneNumber)"));
    }

    private static ProviderConfigProperty property(String name, String label, String help) {
        return new ProviderConfigProperty(name, label, help, ProviderConfigProperty.STRING_TYPE, null);
    }

    @Override
    public void init(Config.Scope config) {
        // No server-level config.
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Nothing to wire.
    }

    @Override
    public void close() {
        // Static resources only.
    }
}
