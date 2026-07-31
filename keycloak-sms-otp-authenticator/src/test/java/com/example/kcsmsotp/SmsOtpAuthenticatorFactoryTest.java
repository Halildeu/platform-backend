package com.example.kcsmsotp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.provider.ProviderConfigProperty;

class SmsOtpAuthenticatorFactoryTest {

    @Test
    void servicesFile_registersExactlyThisFactory() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "META-INF/services/org.keycloak.authentication.AuthenticatorFactory")) {
            assertThat(in).isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            assertThat(content).isEqualTo(SmsOtpAuthenticatorFactory.class.getName());
        }
    }

    @Test
    void serviceLoader_discoversTheFactory_underProviderIdSmsOtp() {
        List<AuthenticatorFactory> loaded = ServiceLoader
                .load(AuthenticatorFactory.class, getClass().getClassLoader())
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(f -> f instanceof SmsOtpAuthenticatorFactory)
                .toList();

        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).getId()).isEqualTo("sms-otp");
    }

    @Test
    void requirementChoices_offerAlternative_theIntendedDeploymentShape() {
        assertThat(new SmsOtpAuthenticatorFactory().getRequirementChoices())
                .contains(AuthenticationExecutionModel.Requirement.ALTERNATIVE,
                        AuthenticationExecutionModel.Requirement.DISABLED);
    }

    @Test
    void configProperties_coverEveryTunable_butNeverTheSecret() {
        List<String> names = new SmsOtpAuthenticatorFactory().getConfigProperties().stream()
                .map(ProviderConfigProperty::getName)
                .toList();

        assertThat(names).containsExactlyInAnyOrder(
                SmsOtpConfig.CFG_TOKEN_URL,
                SmsOtpConfig.CFG_INTENT_URL,
                SmsOtpConfig.CFG_CLIENT_ID,
                SmsOtpConfig.CFG_ORG_ID,
                SmsOtpConfig.CFG_TOPIC_KEY,
                SmsOtpConfig.CFG_TEMPLATE_ID,
                SmsOtpConfig.CFG_TTL_SECONDS,
                SmsOtpConfig.CFG_MAX_ATTEMPTS,
                SmsOtpConfig.CFG_MAX_RESENDS,
                SmsOtpConfig.CFG_PHONE_ATTRIBUTE);
        // Keycloak persists authenticator config plaintext in its DB; the
        // secret must stay env-only. No config key may ever carry it.
        assertThat(names).noneMatch(n -> n.toLowerCase().contains("secret"));
    }

    @Test
    void phoneNormalization_acceptsE164_rejectsEverythingElse() {
        assertThat(SmsOtpAuthenticator.normalizePhone("+905321234567")).isEqualTo("+905321234567");
        assertThat(SmsOtpAuthenticator.normalizePhone("  +905321234567  ")).isEqualTo("+905321234567");
        assertThat(SmsOtpAuthenticator.normalizePhone("05321234567")).isNull();
        assertThat(SmsOtpAuthenticator.normalizePhone("+0532")).isNull();
        assertThat(SmsOtpAuthenticator.normalizePhone("not-a-phone")).isNull();
        assertThat(SmsOtpAuthenticator.normalizePhone("")).isNull();
        assertThat(SmsOtpAuthenticator.normalizePhone(null)).isNull();
    }

    @Test
    void masking_revealsOnlyPrefixAndLastTwoDigits() {
        assertThat(SmsOtpAuthenticator.mask("+905321234567")).isEqualTo("+90********67");
        assertThat(SmsOtpAuthenticator.mask(null)).isEqualTo("***");
        assertThat(SmsOtpAuthenticator.mask("+90")).isEqualTo("***");
    }
}
