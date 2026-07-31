package com.example.kcsmsotp;

import java.util.List;
import java.util.Map;

import org.keycloak.provider.ProviderConfigProperty;

/**
 * The same authenticator, registered a second time for the e-mail channel
 * (gitops#3230).
 *
 * <p>Deliberately a subclass rather than a copied module: everything that is
 * hard here — the hashed code store with its TTL, attempt and resend limits,
 * the token → grant → intent chain, the fail-closed sendability check — is
 * channel-independent. A forked module would have to receive every future fix
 * twice, and one of the two would eventually be missed.
 *
 * <p>What differs is only the default config: the channel, and the topic and
 * template that follow from it. An operator can still override any of it per
 * execution, exactly as with SMS.
 */
public class EmailOtpAuthenticatorFactory extends SmsOtpAuthenticatorFactory {

    public static final String EMAIL_PROVIDER_ID = "email-otp";

    @Override
    public String getId() {
        return EMAIL_PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "E-mail OTP";
    }

    @Override
    public String getHelpText() {
        return "Sends a one-time code to the account's verified e-mail address through the "
                + "notification-orchestrator pipeline and verifies it. Unverified addresses "
                + "disable this alternative — an address anyone could set is not a second "
                + "factor. Client secret comes from the " + SmsOtpConfig.SECRET_ENV
                + " container env, never from this config.";
    }

    /**
     * Same knobs as SMS, with the channel pre-filled so a fresh execution is
     * coherent before anyone edits it.
     */
    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return super.getConfigProperties().stream()
                .map(p -> {
                    if (SmsOtpConfig.CFG_CHANNEL.equals(p.getName())) {
                        ProviderConfigProperty withDefault = new ProviderConfigProperty(
                                p.getName(), p.getLabel(), p.getHelpText(), p.getType(),
                                SmsOtpConfig.CHANNEL_EMAIL);
                        return withDefault;
                    }
                    return p;
                })
                .toList();
    }

    /** Config the flow script writes when it creates this execution. */
    public static Map<String, String> defaultConfig() {
        return Map.of(
                SmsOtpConfig.CFG_CHANNEL, SmsOtpConfig.CHANNEL_EMAIL,
                SmsOtpConfig.CFG_TOPIC_KEY, "auth.mfa.email-otp",
                SmsOtpConfig.CFG_TEMPLATE_ID, "auth.email-otp");
    }
}
