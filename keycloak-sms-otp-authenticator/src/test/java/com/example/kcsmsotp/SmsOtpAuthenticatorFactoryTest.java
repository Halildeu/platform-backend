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
    void servicesFile_registersExactlyTheThreeFactories() throws Exception {
        // Was "exactly this factory" until gitops#3230 added the e-mail
        // channel, and two until gitops#3251 added the gated OTP form. Still
        // exact: an unregistered factory is invisible to Keycloak, and an
        // unexpected one is a supply-chain question.
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "META-INF/services/org.keycloak.authentication.AuthenticatorFactory")) {
            assertThat(in).isNotNull();
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            assertThat(content.lines().map(String::trim).filter(l -> !l.isEmpty()).toList())
                    .containsExactlyInAnyOrder(
                            SmsOtpAuthenticatorFactory.class.getName(),
                            EmailOtpAuthenticatorFactory.class.getName(),
                            MfaOtpFormAuthenticatorFactory.class.getName());
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

        // Two providers now — the e-mail factory is a subclass, so it matches
        // the same instanceof. What must stay true is that their ids differ:
        // a duplicate id means one silently shadows the other in Keycloak.
        assertThat(loaded).hasSize(2);
        assertThat(loaded.stream().map(AuthenticatorFactory::getId).toList())
                .containsExactlyInAnyOrder("sms-otp", "email-otp");
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
                SmsOtpConfig.CFG_GRANT_URL,
                SmsOtpConfig.CFG_INTENT_URL,
                SmsOtpConfig.CFG_CLIENT_ID,
                SmsOtpConfig.CFG_ORG_ID,
                SmsOtpConfig.CFG_TOPIC_KEY,
                SmsOtpConfig.CFG_TEMPLATE_ID,
                SmsOtpConfig.CFG_CHANNEL,
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

    // ── channel parameterisation (gitops#3230) ──────────────────────────

    @Test
    void emailFactory_registersItsOwnIdAndDefaultsToTheEmailChannel() {
        EmailOtpAuthenticatorFactory email = new EmailOtpAuthenticatorFactory();

        assertThat(email.getId()).isEqualTo("email-otp");
        assertThat(email.getId()).isNotEqualTo(new SmsOtpAuthenticatorFactory().getId());
        assertThat(email.defaultConfig())
                .containsEntry(SmsOtpConfig.CFG_CHANNEL, "email")
                .containsEntry(SmsOtpConfig.CFG_TOPIC_KEY, "auth.mfa.email-otp")
                .containsEntry(SmsOtpConfig.CFG_TEMPLATE_ID, "auth.email-otp");
    }

    @Test
    void bothFactoriesExposeTheSameKnobs() {
        // The e-mail factory must not quietly drop a control the SMS one has;
        // that is how a fork starts.
        var smsNames = new SmsOtpAuthenticatorFactory().getConfigProperties()
                .stream().map(p -> p.getName()).sorted().toList();
        var emailNames = new EmailOtpAuthenticatorFactory().getConfigProperties()
                .stream().map(p -> p.getName()).sorted().toList();
        assertThat(emailNames).isEqualTo(smsNames);
    }
}
