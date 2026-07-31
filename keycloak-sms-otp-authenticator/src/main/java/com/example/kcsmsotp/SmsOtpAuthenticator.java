package com.example.kcsmsotp;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

/**
 * SMS one-time-code authenticator (Faz 22 Sec, gitops#3212). Intended as an
 * ALTERNATIVE next to the built-in OTP form inside the requires-mfa
 * conditional subflow: TOTP stays the default and stronger factor; users who
 * opt for SMS pick it via "Try another way".
 *
 * <p>The code is generated here, stored hashed in auth-session notes
 * ({@link SmsOtpCodeStore}) and delivered through the platform notify
 * pipeline ({@link NotifySmsGateway}). SIM-swap exposure is a consciously
 * accepted owner decision for this opt-in lane.
 */
public class SmsOtpAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(SmsOtpAuthenticator.class);

    static final String FORM_TEMPLATE = "sms-otp-form.ftl";
    static final String FIELD_CODE = "smsOtp";
    static final String FIELD_RESEND = "resend";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final SecureRandom rng;
    private final Clock clock;

    public SmsOtpAuthenticator(HttpClient http, ObjectMapper mapper, SecureRandom rng, Clock clock) {
        this.http = http;
        this.mapper = mapper;
        this.rng = rng;
        this.clock = clock;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        SmsOtpConfig cfg = config(context);
        String phone = phoneOf(context.getUser(), cfg);
        if (phone == null || !cfg.isSendable()) {
            // Not usable for this user/deployment — let the sibling ALTERNATIVE
            // (TOTP form) carry the subflow instead of failing the login.
            // Logged because silence here is indistinguishable from "never
            // invoked", and the two have completely different fixes: a missing
            // phone is a user/user-profile problem, a non-sendable config is a
            // deployment problem. No phone number is logged.
            LOG.warnf("sms-otp not usable: phone=%s tokenUrl=%s intentUrl=%s secret=%s",
                    phone == null ? "absent" : "present",
                    cfg.tokenUrl.isBlank() ? "blank" : "set",
                    cfg.intentUrl.isBlank() ? "blank" : "set",
                    cfg.secret.isBlank() ? "blank" : "set");
            context.attempted();
            return;
        }
        LOG.infof("sms-otp selected for user; delivering code via notify");

        SmsOtpCodeStore store = store(cfg);
        Notes notes = new Notes(context.getAuthenticationSession());
        String code = store.issue(notes);
        deliverAndChallenge(context, cfg, store, notes, phone, code);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        SmsOtpConfig cfg = config(context);
        SmsOtpCodeStore store = store(cfg);
        Notes notes = new Notes(context.getAuthenticationSession());
        String phone = phoneOf(context.getUser(), cfg);
        MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();

        if (form.containsKey(FIELD_RESEND)) {
            if (!store.canResend(notes)) {
                challenge(context, phone, "smsOtpResendLimit");
                return;
            }
            String code = store.resend(notes);
            deliverAndChallenge(context, cfg, store, notes, phone, code);
            return;
        }

        SmsOtpCodeStore.VerifyResult result = store.verify(notes, form.getFirst(FIELD_CODE));
        switch (result.status()) {
            case OK -> context.success();
            case EXPIRED -> challenge(context, phone, "smsOtpExpired");
            case INVALID -> context.failureChallenge(
                    AuthenticationFlowError.INVALID_CREDENTIALS,
                    form(context, phone)
                            .setError("smsOtpInvalid", Integer.toString(result.remainingAttempts()))
                            .createForm(FORM_TEMPLATE));
            case TOO_MANY_ATTEMPTS -> context.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
        }
    }

    private void deliverAndChallenge(AuthenticationFlowContext context, SmsOtpConfig cfg,
            SmsOtpCodeStore store, Notes notes, String phone, String code) {
        String localeTag = resolveLocaleTag(context);
        String idempotencyKey = "sms-otp-"
                + context.getAuthenticationSession().getParentSession().getId()
                + "-" + store.resendCount(notes);
        try {
            new NotifySmsGateway(http, mapper, cfg).send(phone, localeTag, code, idempotencyKey,
                    context.getUser().getId(),
                    context.getAuthenticationSession().getParentSession().getId());
            challenge(context, phone, null);
        } catch (SmsSendException e) {
            LOG.warnf("sms-otp send failed (client-side detail withheld from user): %s", e.getMessage());
            challenge(context, phone, "smsOtpSendFailed");
        }
    }

    private void challenge(AuthenticationFlowContext context, String phone, String errorKey) {
        LoginFormsProvider form = form(context, phone);
        if (errorKey != null) {
            form.setError(errorKey);
        }
        Response page = form.createForm(FORM_TEMPLATE);
        context.challenge(page);
    }

    private LoginFormsProvider form(AuthenticationFlowContext context, String phone) {
        return context.form().setAttribute("maskedPhone", mask(phone));
    }

    private SmsOtpConfig config(AuthenticationFlowContext context) {
        return SmsOtpConfig.from(context.getAuthenticatorConfig(), System::getenv);
    }

    private SmsOtpCodeStore store(SmsOtpConfig cfg) {
        return new SmsOtpCodeStore(rng, clock, cfg.ttlSeconds, cfg.maxAttempts, cfg.maxResends);
    }

    private String resolveLocaleTag(AuthenticationFlowContext context) {
        Locale locale = context.getSession().getContext().resolveLocale(context.getUser());
        return locale == null ? "tr" : locale.toLanguageTag();
    }

    static String phoneOf(UserModel user, SmsOtpConfig cfg) {
        return user == null ? null : normalizePhone(user.getFirstAttribute(cfg.phoneAttribute));
    }

    /**
     * Mirrors the notify RecipientRef E.164 pattern; a malformed attribute
     * must disable the alternative, not produce a doomed intent.
     */
    static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String phone = raw.trim();
        return phone.matches("^\\+[1-9][0-9]{7,14}$") ? phone : null;
    }

    static String mask(String phone) {
        if (phone == null || phone.length() < 6) {
            return "***";
        }
        return phone.substring(0, 3)
                + "*".repeat(phone.length() - 5)
                + phone.substring(phone.length() - 2);
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        // Deliberately always true.
        //
        // Keycloak calls this WITHOUT the execution's authenticator config, so
        // the deployment URLs are invisible here; and the phone lives in a
        // user-profile attribute whose visibility in this context is not
        // something an authenticator should depend on. Measured 2026-07-31: a
        // conditional false here made Keycloak skip the execution entirely —
        // no log line, no form, the second factor silently absent — which is
        // the worst failure mode for an MFA lane.
        //
        // Usability is decided in authenticate() instead, where the real
        // config IS available: a user without a phone (or a deployment with
        // no credentials) falls through to attempted() and the sibling TOTP
        // alternative carries the subflow.
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // Phone enrollment is managed in the admin panel (gitops#3211), not by
        // a Keycloak required action.
    }

    @Override
    public void close() {
        // Stateless singleton.
    }

    /** Adapter from auth-session notes to the store's minimal view. */
    private record Notes(AuthenticationSessionModel session) implements SmsOtpCodeStore.Notes {
        @Override
        public String get(String key) {
            return session.getAuthNote(key);
        }

        @Override
        public void set(String key, String value) {
            session.setAuthNote(key, value);
        }
    }
}
