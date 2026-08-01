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

    // ── per-user method allow-list (gitops#3232) ────────────────────────

    private static java.util.List<String> attr(String... values) {
        return java.util.List.of(values);
    }

    @Test
    void noAttribute_meansNoRestriction_soExistingAccountsKeepEveryLane() {
        assertThat(SmsOtpAuthenticator.channelAllowed(attr(), "sms")).isTrue();
        assertThat(SmsOtpAuthenticator.channelAllowed(attr(), "email")).isTrue();
    }

    @Test
    void anEmptyValueIsReadAsNoRestriction_notAsLockEveryoneOut() {
        // An empty list almost certainly means a UI wrote nothing, not that an
        // operator meant "no methods at all". The safe reading of an ambiguous
        // restriction is the one that does not lock people out.
        assertThat(SmsOtpAuthenticator.channelAllowed(attr("", "  "), "sms")).isTrue();
    }

    @Test
    void aNamedListGatesTheChannel() {
        assertThat(SmsOtpAuthenticator.channelAllowed(attr("sms"), "sms")).isTrue();
        assertThat(SmsOtpAuthenticator.channelAllowed(attr("sms"), "email")).isFalse();
        assertThat(SmsOtpAuthenticator.channelAllowed(attr("email"), "email")).isTrue();
    }

    @Test
    void theListToleratesHoweverTheUiHappensToWriteIt() {
        // Multi-valued attribute, one comma-joined value, stray spacing and
        // casing all mean the same thing to an operator; they should mean the
        // same thing here.
        assertThat(SmsOtpAuthenticator.channelAllowed(attr("sms", "email"), "email")).isTrue();
        assertThat(SmsOtpAuthenticator.channelAllowed(attr("sms, EMAIL"), "email")).isTrue();
        assertThat(SmsOtpAuthenticator.channelAllowed(attr(" Email "), "email")).isTrue();
    }

    @Test
    void anUnknownEntryDoesNotAccidentallyAllowEverything() {
        // A typo restricts rather than opens: the list is non-empty, so the
        // channel still has to be named in it.
        assertThat(SmsOtpAuthenticator.channelAllowed(attr("smss"), "sms")).isFalse();
    }
}
