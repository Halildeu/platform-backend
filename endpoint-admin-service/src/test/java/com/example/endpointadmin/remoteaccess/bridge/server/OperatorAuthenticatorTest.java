package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.AuthMethod;
import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.OperatorCredential;
import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.OperatorIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 slice-4c-1 (Codex 019ebe06) — the operator authenticator is fail-closed: only the matching
 * reference token yields a verified identity; a wrong/missing/blank credential is unauthenticated, and an
 * authenticated identity with no subject is still not authenticated.
 */
class OperatorAuthenticatorTest {

    private static final String REF_TOKEN = "ref-operator-token-1";
    private static final String SUBJECT = "operator@acik.com";

    private static OperatorAuthenticator authenticator() {
        return new InMemoryOperatorAuthenticator(REF_TOKEN, SUBJECT);
    }

    private static OperatorCredential bearer(String token) {
        return new OperatorCredential(List.of(), Optional.ofNullable(token));
    }

    @Test
    void theMatchingTokenAuthenticatesTheOperator() {
        OperatorIdentity identity = authenticator().authenticate(bearer(REF_TOKEN));
        assertTrue(identity.isAuthenticated());
        assertEquals(SUBJECT, identity.operatorSubject());
        assertEquals(AuthMethod.JWT_BEARER, identity.authMethod());
    }

    @Test
    void aWrongTokenIsUnauthenticated() {
        OperatorIdentity identity = authenticator().authenticate(bearer("not-the-token"));
        assertFalse(identity.isAuthenticated());
        assertEquals(AuthMethod.UNAUTHENTICATED, identity.authMethod());
    }

    @Test
    void aMissingOrBlankOrNullCredentialIsUnauthenticated() {
        assertFalse(authenticator().authenticate(OperatorCredential.none()).isAuthenticated());
        assertFalse(authenticator().authenticate(bearer("  ")).isAuthenticated());
        assertFalse(authenticator().authenticate(null).isAuthenticated());
    }

    @Test
    void theReferenceCtorRejectsBlankConfig() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryOperatorAuthenticator("  ", SUBJECT));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryOperatorAuthenticator(REF_TOKEN, "  "));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryOperatorAuthenticator(null, SUBJECT));
    }

    @Test
    void theUnauthenticatedIdentityIsTheFailClosedFloor() {
        OperatorIdentity none = OperatorIdentity.unauthenticated();
        assertFalse(none.isAuthenticated());
        assertEquals(AuthMethod.UNAUTHENTICATED, none.authMethod());
    }

    @Test
    void anAuthenticatedFlagWithNoSubjectIsStillNotAuthenticated() {
        // defense-in-depth: a malformed identity (authenticated=true but blank subject) must not pass
        OperatorIdentity malformed = new OperatorIdentity("  ", AuthMethod.JWT_BEARER, true);
        assertFalse(malformed.isAuthenticated());
    }

    @Test
    void anAuthenticatedFlagWithNoRealMethodIsNormalizedToUnauthenticated() {
        // Codex REVISE: authenticated=true with a null/UNAUTHENTICATED method must NOT pass the 4c-2 guard
        assertFalse(new OperatorIdentity("op", AuthMethod.UNAUTHENTICATED, true).isAuthenticated());
        assertFalse(new OperatorIdentity("op", null, true).isAuthenticated());
        // the compact ctor normalizes the inconsistent flag to false
        assertFalse(new OperatorIdentity("op", AuthMethod.UNAUTHENTICATED, true).authenticated());
    }
}
