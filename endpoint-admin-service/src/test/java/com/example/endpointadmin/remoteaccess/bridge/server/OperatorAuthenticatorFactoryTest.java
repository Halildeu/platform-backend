package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticatorFactory.AuthenticatorType;
import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticatorFactory.JwtBearerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Faz 22.6 slice-4c-1b + D10 Workstream-0 (Codex 019ebe06) — the factory's blocking matrix: the placeholder
 * IN_MEMORY authenticator is refused in a prod-like profile; JWT_BEARER builds the real authenticator only with
 * a complete config (else fail-fast); MTLS_CLIENT_CERT stays not-yet-implemented.
 */
class OperatorAuthenticatorFactoryTest {

    private static final String TOKEN = "ref-operator-token-1";
    private static final String SUBJECT = "operator@acik.com";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    // the factory only checks the JWT config fields are present (it does not decode), so a trivial stub decoder
    // is enough to exercise the build path
    private static final JwtDecoder STUB_DECODER = token -> null;

    private static JwtBearerConfig fullJwtConfig() {
        return new JwtBearerConfig(STUB_DECODER, "http://keycloak:8080/realms/serban", "remote-bridge-operator-api",
                "tenant_id", "sub", "realm_access.roles", "remote-bridge-operator");
    }

    @Test
    void inMemoryIsTheReferenceOutsideProd() {
        OperatorAuthenticator a = OperatorAuthenticatorFactory.create(
                AuthenticatorType.IN_MEMORY, TOKEN, SUBJECT, TENANT, null, false);
        assertInstanceOf(InMemoryOperatorAuthenticator.class, a);
    }

    @Test
    void inMemoryIsForbiddenInAProdLikeProfile() {
        assertThrows(IllegalStateException.class, () -> OperatorAuthenticatorFactory.create(
                AuthenticatorType.IN_MEMORY, TOKEN, SUBJECT, TENANT, null, true));
    }

    @Test
    void jwtBearerWithAFullConfigBuildsTheRealAuthenticator() {
        // JWT_BEARER is prod-legal (the real human-to-console authenticator) — even in a prod-like profile
        OperatorAuthenticator a = OperatorAuthenticatorFactory.create(
                AuthenticatorType.JWT_BEARER, null, null, null, fullJwtConfig(), true);
        assertInstanceOf(JwtBearerOperatorAuthenticator.class, a);
    }

    @Test
    void jwtBearerWithMissingOrIncompleteConfigFailsFast() {
        // no config at all
        assertThrows(IllegalStateException.class, () -> OperatorAuthenticatorFactory.create(
                AuthenticatorType.JWT_BEARER, null, null, null, null, false));
        // no decoder
        assertThrows(IllegalStateException.class, () -> OperatorAuthenticatorFactory.create(
                AuthenticatorType.JWT_BEARER, null, null, null,
                new JwtBearerConfig(null, "iss", "aud", "tenant_id", "sub", "realm_access.roles", "op"), false));
        // blank issuer
        assertThrows(IllegalStateException.class, () -> OperatorAuthenticatorFactory.create(
                AuthenticatorType.JWT_BEARER, null, null, null,
                new JwtBearerConfig(STUB_DECODER, "  ", "aud", "tenant_id", "sub", "realm_access.roles", "op"), false));
        // blank required operator role
        assertThrows(IllegalStateException.class, () -> OperatorAuthenticatorFactory.create(
                AuthenticatorType.JWT_BEARER, null, null, null,
                new JwtBearerConfig(STUB_DECODER, "iss", "aud", "tenant_id", "sub", "realm_access.roles", "  "), false));
    }

    @Test
    void mtlsClientCertStaysNotYetImplemented() {
        assertThrows(IllegalStateException.class, () -> OperatorAuthenticatorFactory.create(
                AuthenticatorType.MTLS_CLIENT_CERT, null, null, null, null, false));
    }

    @Test
    void aNullTypeDefaultsToInMemoryOutsideProdButIsForbiddenInProd() {
        assertInstanceOf(InMemoryOperatorAuthenticator.class,
                OperatorAuthenticatorFactory.create(null, TOKEN, SUBJECT, TENANT, null, false));
        assertThrows(IllegalStateException.class,
                () -> OperatorAuthenticatorFactory.create(null, TOKEN, SUBJECT, TENANT, null, true));
    }

    @Test
    void inMemoryWithBlankConfigStillFailsFast() {
        // the InMemoryOperatorAuthenticator ctor refuses a blank token/subject/tenant (config-shaped fail-fast)
        assertThrows(IllegalArgumentException.class, () -> OperatorAuthenticatorFactory.create(
                AuthenticatorType.IN_MEMORY, "  ", SUBJECT, TENANT, null, false));
        assertThrows(IllegalArgumentException.class, () -> OperatorAuthenticatorFactory.create(
                AuthenticatorType.IN_MEMORY, TOKEN, SUBJECT, "  ", null, false));
    }
}
