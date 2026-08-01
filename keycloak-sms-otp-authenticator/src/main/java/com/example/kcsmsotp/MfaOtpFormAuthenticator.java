package com.example.kcsmsotp;

import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.authenticators.browser.OTPFormAuthenticator;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Keycloak's own OTP form, with the per-user method allow-list in front of it
 * (gitops#3251).
 *
 * <p>Stock {@code auth-otp-form} decides on one thing: does this user have an
 * OTP credential. That is why the panel's method checkboxes could govern SMS
 * and e-mail but not the authenticator app — nothing in the stock lane reads
 * {@code mfaMethods}. This subclass adds exactly that one question and changes
 * nothing else.
 *
 * <p><b>Delegating rather than reimplementing is the point.</b> TOTP is the
 * strongest of the three factors and the sensible fallback when a phone or
 * mailbox is unavailable. Re-writing its form, its validation and its
 * enrolment path in order to add a boolean would put the most reliable lane at
 * risk to gate it. Everything hard stays Keycloak's.
 *
 * <p>Refusal uses {@code attempted()} and a false {@code configuredFor}, the
 * same two signals the SMS lane already uses when a phone number is missing, so
 * a disallowed authenticator app disappears from "Try another way" instead of
 * failing the login — the sibling alternatives still carry it.
 */
public class MfaOtpFormAuthenticator extends OTPFormAuthenticator {

    private static final Logger LOG = Logger.getLogger(MfaOtpFormAuthenticator.class);

    static final String CFG_METHODS_ATTRIBUTE = SmsOtpConfig.CFG_METHODS_ATTRIBUTE;
    static final String CFG_PHONE_ATTRIBUTE = SmsOtpConfig.CFG_PHONE_ATTRIBUTE;
    static final String DEFAULT_METHODS_ATTRIBUTE = "mfaMethods";
    static final String DEFAULT_PHONE_ATTRIBUTE = "phoneNumber";

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        if (!allowedFor(context.getUser(), config(context))) {
            // Not a failure: the account is restricted to other methods. Same
            // shape as the SMS lane with no phone number — hand the subflow to
            // the siblings rather than dead-ending the login.
            LOG.info("mfa-otp-form: authenticator app not among this user's allowed methods; skipping");
            context.attempted();
            return;
        }
        super.authenticate(context);
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        if (!allowedFor(user, Map.of())) {
            return false;
        }
        return super.configuredFor(session, realm, user);
    }

    /**
     * Never push a user whose allow-list excludes the authenticator app into
     * enrolling one. Without this, a restricted account could still be handed
     * the CONFIGURE_TOTP required action and be asked to set up the very factor
     * the operator just switched off.
     */
    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        if (!allowedFor(user, Map.of())) {
            return;
        }
        super.setRequiredActions(session, realm, user);
    }

    private boolean allowedFor(UserModel user, Map<String, String> cfg) {
        if (user == null) {
            return false;
        }
        String methodsAttr = value(cfg, CFG_METHODS_ATTRIBUTE, DEFAULT_METHODS_ATTRIBUTE);
        String phoneAttr = value(cfg, CFG_PHONE_ATTRIBUTE, DEFAULT_PHONE_ATTRIBUTE);
        List<String> raw = user.getAttributeStream(methodsAttr).toList();
        boolean smsUsable = user.getAttributeStream(phoneAttr)
                .anyMatch(v -> v != null && !v.isBlank());
        boolean emailUsable = user.isEmailVerified()
                && user.getEmail() != null && !user.getEmail().isBlank();
        return TotpMethodGate.allowed(raw, smsUsable, emailUsable);
    }

    private static Map<String, String> config(AuthenticationFlowContext context) {
        AuthenticatorConfigModel model = context.getAuthenticatorConfig();
        return model == null || model.getConfig() == null ? Map.of() : model.getConfig();
    }

    private static String value(Map<String, String> cfg, String key, String fallback) {
        String v = cfg.get(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
