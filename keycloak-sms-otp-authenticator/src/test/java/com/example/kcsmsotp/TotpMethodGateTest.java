package com.example.kcsmsotp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The authenticator-app allow-list gate (gitops#3251).
 *
 * <p>Two properties matter here and they pull in opposite directions: a
 * restriction has to actually restrict, and a restriction must never be able to
 * lock somebody out of their own account. Both are pinned.
 */
class TotpMethodGateTest {

    private static final boolean PHONE = true;
    private static final boolean NO_PHONE = false;
    private static final boolean VERIFIED_MAIL = true;
    private static final boolean NO_MAIL = false;

    @Test
    void absentList_allowsTheAuthenticatorApp() {
        // Every account that predates the allow-list keeps the lane it had.
        assertThat(TotpMethodGate.allowed(List.of(), PHONE, VERIFIED_MAIL)).isTrue();
    }

    @Test
    void listOfBlanks_isTreatedAsAbsent() {
        // A UI that wrote an empty value meant "no restriction", not "no methods".
        assertThat(TotpMethodGate.allowed(List.of("", "   "), PHONE, VERIFIED_MAIL)).isTrue();
    }

    @Test
    void listNamingTotp_allowsIt() {
        assertThat(TotpMethodGate.allowed(List.of("totp"), PHONE, VERIFIED_MAIL)).isTrue();
    }

    @Test
    void listNamingTotp_toleratesCasingAndCommas() {
        assertThat(TotpMethodGate.allowed(List.of(" SMS , TOTP "), PHONE, VERIFIED_MAIL)).isTrue();
    }

    @Test
    void smsOnly_withAPhone_withholdsTheAuthenticatorApp() {
        // The restriction does its job: SMS is genuinely usable, so this is a
        // real choice by the operator rather than an accidental dead end.
        assertThat(TotpMethodGate.allowed(List.of("sms"), PHONE, VERIFIED_MAIL)).isFalse();
    }

    @Test
    void emailOnly_withAVerifiedAddress_withholdsTheAuthenticatorApp() {
        assertThat(TotpMethodGate.allowed(List.of("email"), NO_PHONE, VERIFIED_MAIL)).isFalse();
    }

    @Test
    void smsOnly_withoutAPhone_stillAllowsTheAuthenticatorApp() {
        // The lockout case. "SMS only" on an account with no phone number would
        // otherwise close every lane on a user who still carries requires-mfa —
        // and nothing in the panel would look wrong.
        assertThat(TotpMethodGate.allowed(List.of("sms"), NO_PHONE, VERIFIED_MAIL)).isTrue();
    }

    @Test
    void emailOnly_withoutAVerifiedAddress_stillAllowsTheAuthenticatorApp() {
        assertThat(TotpMethodGate.allowed(List.of("email"), PHONE, NO_MAIL)).isTrue();
    }

    @Test
    void smsAndEmail_withOneOfThemUsable_withholdsTheAuthenticatorApp() {
        // One usable sibling is enough; the fallback only fires when none is.
        assertThat(TotpMethodGate.allowed(List.of("sms", "email"), PHONE, NO_MAIL)).isFalse();
    }

    @Test
    void smsAndEmail_withNeitherUsable_allowsTheAuthenticatorApp() {
        assertThat(TotpMethodGate.allowed(List.of("sms", "email"), NO_PHONE, NO_MAIL)).isTrue();
    }

    @Test
    void aTypoRestrictsToNothingRecognisable_soTheAuthenticatorAppSurvives() {
        // "smss" names no lane this deployment knows. Without the fallback the
        // account would have no second factor at all — an allow-list must not be
        // able to produce that by accident.
        assertThat(TotpMethodGate.allowed(List.of("smss"), PHONE, VERIFIED_MAIL)).isTrue();
    }

    @Test
    void listAllows_matchesTheSharedParsingUsedByTheOtherLanes() {
        // The gate must not grow a second dialect of the same attribute.
        assertThat(TotpMethodGate.listAllows(List.of("totp")))
                .isEqualTo(SmsOtpAuthenticator.channelAllowed(List.of("totp"), "totp"));
        assertThat(TotpMethodGate.listAllows(List.of("sms")))
                .isEqualTo(SmsOtpAuthenticator.channelAllowed(List.of("sms"), "totp"));
    }
}
