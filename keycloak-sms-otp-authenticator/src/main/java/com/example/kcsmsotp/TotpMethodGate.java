package com.example.kcsmsotp;

import java.util.List;

/**
 * Whether the authenticator-app lane may be offered to a user, given the
 * per-user method allow-list (gitops#3251).
 *
 * <p>Split out as a pure function on purpose. The decision is the whole feature
 * — the authenticator around it only wires Keycloak's {@code OTPFormAuthenticator}
 * to the answer — and a pure function is testable without standing up a
 * {@code UserModel} or adding a mocking dependency to this module, exactly as
 * {@link SmsOtpAuthenticator#channelAllowed} already is.
 */
final class TotpMethodGate {

    static final String CHANNEL = "totp";

    private TotpMethodGate() {
    }

    /**
     * The allow-list half: absent or empty means unrestricted, and a list that
     * names channels has to name this one. Identical semantics to the SMS and
     * e-mail lanes, so an operator does not have to learn a second rule for the
     * third checkbox.
     */
    static boolean listAllows(List<String> rawValues) {
        return SmsOtpAuthenticator.channelAllowed(rawValues, CHANNEL);
    }

    /**
     * The decision the authenticator acts on.
     *
     * <p>An allow-list that can strand somebody is worse than no allow-list. A
     * restriction of {@code ["sms"]} on an account with no phone number leaves
     * SMS unusable, e-mail disallowed and — once this gate exists — the
     * authenticator app disallowed too: every lane closed, on an account that
     * still carries {@code requires-mfa}. The person cannot log in, and nothing
     * in the panel looks wrong.
     *
     * <p>So the authenticator app survives a restriction that would otherwise
     * close the last door. This is the same principle already written into the
     * storage rules — absent or empty means unrestricted, an unknown method is
     * refused rather than stored — applied to the one case those two cannot
     * catch: a restriction that is individually valid but collectively empty
     * <em>for this particular account</em>.
     *
     * <p>Deliberately asymmetric: the fallback only ever <em>widens</em>. An
     * operator who restricts to SMS and has set a phone number gets exactly
     * what they asked for; the widening happens only where the alternative is
     * a locked-out user.
     *
     * @param rawValues     the raw {@code mfaMethods} attribute values
     * @param smsUsable     the account has a phone number the SMS lane can reach
     * @param emailUsable   the account has a verified address the e-mail lane can reach
     */
    static boolean allowed(List<String> rawValues, boolean smsUsable, boolean emailUsable) {
        if (listAllows(rawValues)) {
            return true;
        }
        boolean otherLaneUsable =
                (smsUsable && SmsOtpAuthenticator.channelAllowed(rawValues, SmsOtpConfig.CHANNEL_SMS))
                        || (emailUsable && SmsOtpAuthenticator.channelAllowed(rawValues, SmsOtpConfig.CHANNEL_EMAIL));
        return !otherLaneUsable;
    }
}
