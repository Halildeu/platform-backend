package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticatorFactory.AuthenticatorType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Faz 22.6 slice-4c-1b (Codex 019ebe06) — the factory's blocking matrix: the placeholder IN_MEMORY
 * authenticator is refused in a prod-like profile, and the real authenticators (not yet implemented) fail
 * fast rather than half-wiring an auth path.
 */
class OperatorAuthenticatorFactoryTest {

    private static final String TOKEN = "ref-operator-token-1";
    private static final String SUBJECT = "operator@acik.com";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    @Test
    void inMemoryIsTheReferenceOutsideProd() {
        OperatorAuthenticator a = OperatorAuthenticatorFactory.create(
                AuthenticatorType.IN_MEMORY, TOKEN, SUBJECT, TENANT, false);
        assertInstanceOf(InMemoryOperatorAuthenticator.class, a);
    }

    @Test
    void inMemoryIsForbiddenInAProdLikeProfile() {
        assertThrows(IllegalStateException.class, () -> OperatorAuthenticatorFactory.create(
                AuthenticatorType.IN_MEMORY, TOKEN, SUBJECT, TENANT, true));
    }

    @Test
    void aRealAuthenticatorTypeFailsFastUntilImplemented() {
        assertThrows(IllegalStateException.class, () -> OperatorAuthenticatorFactory.create(
                AuthenticatorType.MTLS_CLIENT_CERT, null, null, null, false));
        assertThrows(IllegalStateException.class, () -> OperatorAuthenticatorFactory.create(
                AuthenticatorType.JWT_BEARER, null, null, null, true));
    }

    @Test
    void aNullTypeDefaultsToInMemoryOutsideProdButIsForbiddenInProd() {
        assertInstanceOf(InMemoryOperatorAuthenticator.class,
                OperatorAuthenticatorFactory.create(null, TOKEN, SUBJECT, TENANT, false));
        assertThrows(IllegalStateException.class,
                () -> OperatorAuthenticatorFactory.create(null, TOKEN, SUBJECT, TENANT, true));
    }

    @Test
    void inMemoryWithBlankConfigStillFailsFast() {
        // the InMemoryOperatorAuthenticator ctor refuses a blank token/subject/tenant (config-shaped fail-fast)
        assertThrows(IllegalArgumentException.class, () -> OperatorAuthenticatorFactory.create(
                AuthenticatorType.IN_MEMORY, "  ", SUBJECT, TENANT, false));
        assertThrows(IllegalArgumentException.class, () -> OperatorAuthenticatorFactory.create(
                AuthenticatorType.IN_MEMORY, TOKEN, SUBJECT, "  ", false));
    }
}
