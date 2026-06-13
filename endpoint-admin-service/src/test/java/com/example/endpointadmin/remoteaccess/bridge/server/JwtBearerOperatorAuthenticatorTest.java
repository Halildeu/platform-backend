package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.AuthMethod;
import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.OperatorCredential;
import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.OperatorIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 D10 Workstream-0 (Codex 019ebe06; 3-line consensus JWT/Keycloak) — the operator JWT authenticator
 * is fail-closed: it authenticates ONLY a token whose signature decodes AND whose iss / aud / canonical-UUID
 * tenant / wire-valid subject / required operator role all check out; every other case (decoder fault, wrong
 * issuer/audience, bad tenant/subject, missing role, no bearer) yields {@code unauthenticated()} — never throws.
 */
class JwtBearerOperatorAuthenticatorTest {

    private static final String ISS = "http://keycloak:8080/realms/serban";
    private static final String AUD = "remote-bridge-operator-api";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "operator@acik.com";
    private static final String ROLE = "remote-bridge-operator";

    /** Build a Jwt with the given claims (null = omit). Token value is irrelevant — the stub keys off the bearer. */
    private static Jwt jwt(String iss, List<String> aud, Object tenant, String subject, Object realmAccess) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256");
        if (iss != null) {
            b.claim("iss", iss);
        }
        if (aud != null) {
            b.audience(aud);
        }
        if (tenant != null) {
            b.claim("tenant_id", tenant);
        }
        if (subject != null) {
            b.claim("sub", subject);
        }
        if (realmAccess != null) {
            b.claim("realm_access", realmAccess);
        }
        return b.build();
    }

    private static Object realmRoles(String... roles) {
        return Map.of("roles", List.of(roles));
    }

    /** A decoder that returns {@code jwt} for the bearer "good" and throws (invalid signature) for anything else. */
    private static JwtDecoder decoderReturning(Jwt jwt) {
        return token -> {
            if ("good".equals(token)) {
                return jwt;
            }
            throw new JwtException("invalid token");
        };
    }

    private static JwtBearerOperatorAuthenticator authenticator(JwtDecoder decoder) {
        return new JwtBearerOperatorAuthenticator(decoder, ISS, AUD, "tenant_id", "sub", "realm_access.roles", ROLE);
    }

    private static OperatorCredential bearer(String token) {
        return new OperatorCredential(List.of(), Optional.ofNullable(token));
    }

    @Test
    void aValidOperatorTokenAuthenticates() {
        OperatorIdentity id = authenticator(decoderReturning(jwt(ISS, List.of(AUD), TENANT, SUBJECT, realmRoles(ROLE))))
                .authenticate(bearer("good"));
        assertTrue(id.isAuthenticated());
        assertEquals(TENANT, id.tenantId());
        assertEquals(SUBJECT, id.operatorSubject());
        assertEquals(AuthMethod.JWT_BEARER, id.authMethod());
    }

    @Test
    void aDecoderFailureIsUnauthenticated() {
        // a "bad" bearer makes the decoder throw (bad signature / expired / malformed) → fail-closed, no throw
        assertFalse(authenticator(decoderReturning(jwt(ISS, List.of(AUD), TENANT, SUBJECT, realmRoles(ROLE))))
                .authenticate(bearer("bad")).isAuthenticated());
    }

    @Test
    void aMissingOrBlankBearerIsUnauthenticated() {
        JwtBearerOperatorAuthenticator a =
                authenticator(decoderReturning(jwt(ISS, List.of(AUD), TENANT, SUBJECT, realmRoles(ROLE))));
        assertFalse(a.authenticate(bearer(null)).isAuthenticated());
        assertFalse(a.authenticate(bearer("  ")).isAuthenticated());
        assertFalse(a.authenticate(null).isAuthenticated());
        assertFalse(a.authenticate(OperatorCredential.none()).isAuthenticated());
    }

    @Test
    void aWrongIssuerIsUnauthenticated() {
        assertFalse(authenticator(decoderReturning(jwt("http://evil/realms/x", List.of(AUD), TENANT, SUBJECT,
                realmRoles(ROLE)))).authenticate(bearer("good")).isAuthenticated());
    }

    @Test
    void aMissingOrWrongAudienceIsUnauthenticated() {
        // a token minted for ANOTHER service must not be replayable to the bridge
        assertFalse(authenticator(decoderReturning(jwt(ISS, List.of("other-service"), TENANT, SUBJECT,
                realmRoles(ROLE)))).authenticate(bearer("good")).isAuthenticated());
        assertFalse(authenticator(decoderReturning(jwt(ISS, null, TENANT, SUBJECT, realmRoles(ROLE))))
                .authenticate(bearer("good")).isAuthenticated());
    }

    @Test
    void aNonCanonicalOrMissingTenantIsUnauthenticated() {
        // uppercase UUID is non-canonical — UUID.fromString accepts it but toString() lowercases it, so the
        // strict round-trip rejects it (the tenancy boundary must be unambiguous). Uses hex letters so the
        // uppercasing actually changes the string.
        assertFalse(authenticator(decoderReturning(jwt(ISS, List.of(AUD), "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA",
                SUBJECT, realmRoles(ROLE)))).authenticate(bearer("good")).isAuthenticated());
        // not a UUID at all
        assertFalse(authenticator(decoderReturning(jwt(ISS, List.of(AUD), "tenant-1", SUBJECT, realmRoles(ROLE))))
                .authenticate(bearer("good")).isAuthenticated());
        // missing tenant → no tenancy boundary → unauthenticated
        assertFalse(authenticator(decoderReturning(jwt(ISS, List.of(AUD), null, SUBJECT, realmRoles(ROLE))))
                .authenticate(bearer("good")).isAuthenticated());
    }

    @Test
    void anInvalidOrMissingSubjectIsUnauthenticated() {
        // a subject with a space fails the wire-id charset (audit/log-injection guard)
        assertFalse(authenticator(decoderReturning(jwt(ISS, List.of(AUD), TENANT, "bad subject", realmRoles(ROLE))))
                .authenticate(bearer("good")).isAuthenticated());
        assertFalse(authenticator(decoderReturning(jwt(ISS, List.of(AUD), TENANT, null, realmRoles(ROLE))))
                .authenticate(bearer("good")).isAuthenticated());
    }

    @Test
    void aMissingOperatorRoleIsUnauthenticated() {
        // realm_access.roles WITHOUT the required role → authenticated-but-unauthorized → fail-closed
        assertFalse(authenticator(decoderReturning(jwt(ISS, List.of(AUD), TENANT, SUBJECT, realmRoles("other-role"))))
                .authenticate(bearer("good")).isAuthenticated());
        // no realm_access claim at all
        assertFalse(authenticator(decoderReturning(jwt(ISS, List.of(AUD), TENANT, SUBJECT, null)))
                .authenticate(bearer("good")).isAuthenticated());
    }

    @Test
    void aFlatRoleClaimPathIsAlsoSupported() {
        // a flat "roles" claim path (no dot) reads a top-level list instead of the nested realm_access.roles
        JwtBearerOperatorAuthenticator a = new JwtBearerOperatorAuthenticator(
                decoderReturning(Jwt.withTokenValue("t").header("alg", "RS256")
                        .claim("iss", ISS).audience(List.of(AUD)).claim("tenant_id", TENANT)
                        .claim("sub", SUBJECT).claim("roles", List.of(ROLE)).build()),
                ISS, AUD, "tenant_id", "sub", "roles", ROLE);
        assertTrue(a.authenticate(bearer("good")).isAuthenticated());
    }

    @Test
    void nullDecoderOrBlankConfigRejectedAtConstruction() {
        assertThrows(NullPointerException.class, () -> new JwtBearerOperatorAuthenticator(
                null, ISS, AUD, "tenant_id", "sub", "realm_access.roles", ROLE));
        JwtDecoder decoder = token -> null;
        assertThrows(IllegalArgumentException.class, () -> new JwtBearerOperatorAuthenticator(
                decoder, "  ", AUD, "tenant_id", "sub", "realm_access.roles", ROLE));
        assertThrows(IllegalArgumentException.class, () -> new JwtBearerOperatorAuthenticator(
                decoder, ISS, AUD, "tenant_id", "sub", "realm_access.roles", "  "));
    }
}
