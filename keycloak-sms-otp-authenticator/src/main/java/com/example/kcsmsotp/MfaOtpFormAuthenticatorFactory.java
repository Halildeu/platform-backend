package com.example.kcsmsotp;

import java.util.List;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.credential.OTPCredentialModel;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * Registers {@link MfaOtpFormAuthenticator} as a drop-in replacement for stock
 * {@code auth-otp-form} in the privileged flow (gitops#3251).
 *
 * <p>A separate provider id rather than an override of Keycloak's own: the
 * swap then happens per flow, is visible in the flow listing, and a realm that
 * has not been rewired keeps the stock lane untouched. It is also what lets the
 * gitops script key the whole change off "does this provider exist", exactly as
 * the SMS and e-mail lanes already do — on a Keycloak still running the old jar
 * nothing changes and no half-built flow can strand a login.
 */
public class MfaOtpFormAuthenticatorFactory implements AuthenticatorFactory {

    public static final String PROVIDER_ID = "mfa-otp-form";

    private static final MfaOtpFormAuthenticator SINGLETON = new MfaOtpFormAuthenticator();

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
        AuthenticationExecutionModel.Requirement.REQUIRED,
        AuthenticationExecutionModel.Requirement.ALTERNATIVE,
        AuthenticationExecutionModel.Requirement.DISABLED
    };

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return SINGLETON;
    }

    @Override
    public String getDisplayType() {
        return "OTP Form (method allow-list)";
    }

    @Override
    public String getReferenceCategory() {
        // Same category as the stock form so Keycloak keeps treating this as
        // the OTP factor — the credential it validates is unchanged.
        return OTPCredentialModel.TYPE;
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return true;
    }

    @Override
    public String getHelpText() {
        return "Keycloak's OTP form, offered only when the account's mfaMethods allow-list "
                + "permits the authenticator app. An empty or absent list allows it, and it "
                + "is offered regardless when no other second factor is usable for the "
                + "account — a restriction must not be able to lock someone out.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        ProviderConfigProperty methods = new ProviderConfigProperty();
        methods.setName(MfaOtpFormAuthenticator.CFG_METHODS_ATTRIBUTE);
        methods.setLabel("Methods attribute");
        methods.setType(ProviderConfigProperty.STRING_TYPE);
        methods.setDefaultValue(MfaOtpFormAuthenticator.DEFAULT_METHODS_ATTRIBUTE);
        methods.setHelpText("User attribute holding the per-user allow-list of one-time-code "
                + "methods. Absent or empty means unrestricted.");

        ProviderConfigProperty phone = new ProviderConfigProperty();
        phone.setName(MfaOtpFormAuthenticator.CFG_PHONE_ATTRIBUTE);
        phone.setLabel("Phone attribute");
        phone.setType(ProviderConfigProperty.STRING_TYPE);
        phone.setDefaultValue(MfaOtpFormAuthenticator.DEFAULT_PHONE_ATTRIBUTE);
        phone.setHelpText("User attribute the SMS lane delivers to. Read here only to answer "
                + "whether another lane is usable before a restriction is allowed to close "
                + "this one.");

        return List.of(methods, phone);
    }

    @Override
    public void init(Config.Scope config) {
        // no-op
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }
}
