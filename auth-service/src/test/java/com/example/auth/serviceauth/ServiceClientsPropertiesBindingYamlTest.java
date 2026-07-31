package com.example.auth.serviceauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;

/**
 * Proves the nested client-registration shape used by application-k8s.yml binds the
 * credential and its least-privilege policy as one unit.
 */
class ServiceClientsPropertiesBindingYamlTest {

    @Test
    void keycloakSmsOtpClient_bindsAsSingleAudienceSinglePermissionExplicitOnly() {
        // gitops#3212: the Keycloak SMS-OTP authenticator delivers MFA codes
        // through the notify pipeline, whose internal path only honours SVC_
        // authorities minted by auth-service — hence Keycloak itself is a
        // service client. This pins the identity to its narrowest shape: one
        // audience, one permission, explicit-permissions required. Widening
        // any of the three must fail this test, because a leaked SPI
        // credential must be able to submit notification intents and nothing
        // else.
        ServiceClientsProperties properties = bindFromYaml("""
                security:
                  service-clients:
                    clients:
                      keycloak-sms-otp:
                        secret: test-secret
                        allowed-audiences:
                          - notification-orchestrator
                        allowed-permissions:
                          - notify:intents:system
                        require-explicit-permissions: true
                """);

        ServiceClientsProperties.ClientRegistration registration =
                properties.getClients().get("keycloak-sms-otp");

        assertThat(registration).isNotNull();
        assertThat(registration.getAllowedAudiences())
                .containsExactly("notification-orchestrator");
        assertThat(registration.getAllowedPermissions())
                .containsExactly("notify:intents:system");
        assertThat(registration.isRequireExplicitPermissions()).isTrue();
    }

    @Test
    void productionYamlShape_bindsSecretAudiencePermissionAndExplicitPermissionPolicy() {
        ServiceClientsProperties properties = bindFromYaml("""
                security:
                  service-clients:
                    clients:
                      meeting-ai:
                        secret: test-secret
                        allowed-audiences:
                          - meeting-service
                        allowed-permissions:
                          - meeting:analysis-result:write
                        require-explicit-permissions: true
                """);

        ServiceClientsProperties.ClientRegistration registration =
                properties.getClients().get("meeting-ai");

        assertThat(registration).isNotNull();
        assertThat(registration.getSecret()).isEqualTo("test-secret");
        assertThat(registration.getAllowedAudiences()).containsExactly("meeting-service");
        assertThat(registration.getAllowedPermissions())
                .containsExactly("meeting:analysis-result:write");
        assertThat(registration.isRequireExplicitPermissions()).isTrue();
    }

    @Test
    void blankSecret_keepsRegistrationDisabledWithoutDroppingItsPolicy() {
        ServiceClientsProperties properties = bindFromYaml("""
                security:
                  service-clients:
                    clients:
                      meeting-ai:
                        secret: ""
                        allowed-audiences: [meeting-service]
                        allowed-permissions: [meeting:analysis-result:write]
                """);

        ServiceClientsProperties.ClientRegistration registration =
                properties.getClients().get("meeting-ai");

        assertThat(registration).isNotNull();
        assertThat(registration.getSecret()).isBlank();
        assertThat(registration.getAllowedAudiences()).containsExactly("meeting-service");
        assertThat(registration.getAllowedPermissions())
                .containsExactly("meeting:analysis-result:write");
        assertThat(registration.isRequireExplicitPermissions()).isTrue();
    }

    @Test
    void absentClientPolicy_bindsEmptyCeilingsFailClosed() {
        ServiceClientsProperties properties = bindFromYaml("""
                security:
                  service-clients:
                    clients:
                      legacy-client:
                        secret: test-secret
                """);

        ServiceClientsProperties.ClientRegistration registration =
                properties.getClients().get("legacy-client");

        assertThat(registration).isNotNull();
        assertThat(registration.getAllowedAudiences()).isEmpty();
        assertThat(registration.getAllowedPermissions()).isEmpty();
        assertThat(registration.isRequireExplicitPermissions()).isTrue();
    }

    private ServiceClientsProperties bindFromYaml(String yaml) {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        StandardEnvironment environment = new StandardEnvironment();
        try {
            loader.load(
                            "binding-test",
                            new EncodedResource(
                                            new ByteArrayResource(yaml.getBytes()))
                                    .getResource())
                    .forEach(source -> environment.getPropertySources().addFirst(source));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to load test yaml", exception);
        }
        return Binder.get(environment)
                .bind("security.service-clients", ServiceClientsProperties.class)
                .get();
    }
}
