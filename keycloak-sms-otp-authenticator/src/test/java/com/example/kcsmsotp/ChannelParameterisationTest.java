package com.example.kcsmsotp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.models.AuthenticatorConfigModel;

/**
 * The channel is the only thing that separates the SMS and e-mail second
 * factors (gitops#3230). These pin the three places that actually differ, so
 * a future edit cannot half-move one of them: the notify recipient key, the
 * default topic/template pair, and the fallback for configs written before
 * the key existed.
 */
class ChannelParameterisationTest {

    private static SmsOtpConfig cfg(Map<String, String> map) {
        AuthenticatorConfigModel model = new AuthenticatorConfigModel();
        model.setConfig(map);
        return SmsOtpConfig.from(model, name -> "secret-from-env");
    }

    @Test
    void configWrittenBeforeTheChannelKeyExisted_staysSms() {
        // The live SMS execution carries no delivery-channel entry. It must
        // keep behaving exactly as it did, including its topic and template.
        SmsOtpConfig c = cfg(Map.of("auth-token-url", "http://auth/oauth2/token"));

        assertThat(c.channel).isEqualTo("sms");
        assertThat(c.isEmail()).isFalse();
        assertThat(c.recipientKey()).isEqualTo("phone");
        assertThat(c.topicKey).isEqualTo("auth.mfa.sms-otp");
        assertThat(c.templateId).isEqualTo("auth.sms-otp");
    }

    @Test
    void namingTheChannelIsEnough_topicAndTemplateFollow() {
        SmsOtpConfig c = cfg(Map.of("delivery-channel", "email"));

        assertThat(c.isEmail()).isTrue();
        assertThat(c.recipientKey()).isEqualTo("email");
        assertThat(c.topicKey).isEqualTo("auth.mfa.email-otp");
        assertThat(c.templateId).isEqualTo("auth.email-otp");
    }

    @Test
    void explicitTopicAndTemplateStillWin() {
        SmsOtpConfig c = cfg(Map.of(
                "delivery-channel", "email",
                "topic-key", "auth.mfa.custom",
                "template-id", "auth.custom"));

        assertThat(c.topicKey).isEqualTo("auth.mfa.custom");
        assertThat(c.templateId).isEqualTo("auth.custom");
    }

    @Test
    void anUnknownChannelFallsBackToSmsRatherThanShippingSomethingUndeliverable() {
        SmsOtpConfig c = cfg(Map.of("delivery-channel", "carrier-pigeon"));

        assertThat(c.channel).isEqualTo("sms");
    }

    @Test
    void emailAddressesAreValidatedLikePhoneNumbersAre() {
        assertThat(SmsOtpAuthenticator.normalizeEmail("  ops@acik.com ")).isEqualTo("ops@acik.com");
        assertThat(SmsOtpAuthenticator.normalizeEmail("not-an-address")).isNull();
        assertThat(SmsOtpAuthenticator.normalizeEmail("")).isNull();
        assertThat(SmsOtpAuthenticator.normalizeEmail(null)).isNull();
    }
}
