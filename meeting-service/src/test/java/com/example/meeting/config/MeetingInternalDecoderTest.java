package com.example.meeting.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.meeting.security.FallbackJwtDecoder;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * ai#244 BE-1b — decoder-layer test with REAL RS256-signed tokens.
 *
 * <p>Wires the exact production {@link SecurityConfig#buildServiceValidator}
 * issuer + audience validators onto a Keycloak decoder and an auth-service
 * SERVICE decoder, wraps them in the production {@link FallbackJwtDecoder}, and
 * exercises the full decode → convert path with genuinely signed tokens
 * (No-Fake-Work: a real signature + real validator rejection, not a mock).
 *
 * <ul>
 *   <li>Correct service token (svc key, iss=auth-service, aud=meeting-service,
 *       perm) decodes via the fallback and the converter grants the SVC_
 *       authority.</li>
 *   <li>Wrong audience ⇒ the decoder rejects it (would surface as 401).</li>
 *   <li>A valid Keycloak token carrying a perm claim decodes, but the converter
 *       withholds the SVC_ authority (end-to-end iss-guard).</li>
 *   <li>A token forging iss=auth-service but signed with the wrong key is
 *       rejected (no service signing key ⇒ no service identity).</li>
 *   <li>A decoder init/config fault propagates and is NOT masked by falling
 *       through to the next decoder.</li>
 * </ul>
 */
class MeetingInternalDecoderTest {

    private static final String KC_ISSUER = "http://localhost:8081/realms/serban";
    private static final String SVC_ISSUER = "auth-service";
    private static final String AUDIENCE = "meeting-service";
    private static final String PERM = "meeting:analysis-result:write";
    private static final String SVC_WRITE = "SVC_" + PERM;

    private static KeyPair kcKeys;
    private static KeyPair svcKeys;

    /** Fallback(keycloakDecoder, serviceDecoder) — production validator wiring. */
    private static JwtDecoder fallbackDecoder;

    /** Converter with the default "auth-service" service issuer. */
    private static final JwtAuthenticationConverter CONVERTER =
            new SecurityConfig(new MockEnvironment()).jwtAuthenticationConverter();

    @BeforeAll
    static void setUp() throws Exception {
        kcKeys = rsa();
        svcKeys = rsa();

        NimbusJwtDecoder keycloakDecoder =
                NimbusJwtDecoder.withPublicKey((RSAPublicKey) kcKeys.getPublic()).build();
        keycloakDecoder.setJwtValidator(SecurityConfig.buildServiceValidator(
                KC_ISSUER, List.of(AUDIENCE), List.of("frontend", "account")));

        NimbusJwtDecoder serviceDecoder =
                NimbusJwtDecoder.withPublicKey((RSAPublicKey) svcKeys.getPublic()).build();
        serviceDecoder.setJwtValidator(SecurityConfig.buildServiceValidator(
                SVC_ISSUER, List.of(AUDIENCE), List.of()));

        fallbackDecoder = new FallbackJwtDecoder(List.of(keycloakDecoder, serviceDecoder));
    }

    // ── Correct service token → decodes via fallback + SVC_ authority ────────

    @Test
    void serviceToken_correctIssuerAudiencePerm_decodesViaFallback_andConverterGrantsSvc() throws Exception {
        String token = sign(svcKeys, new JWTClaimsSet.Builder()
                .subject("meeting-ai-service")
                .issuer(SVC_ISSUER)
                .audience(AUDIENCE)
                .claim("svc", "meeting-ai-service")
                .claim("perm", List.of(PERM))
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000)));

        Jwt jwt = fallbackDecoder.decode(token); // keycloak decoder rejects (issuer) → service decoder wins
        assertEquals(SVC_ISSUER, jwt.getClaimAsString("iss"));
        assertTrue(authorities(jwt).contains(SVC_WRITE),
                "a correctly-issued, correctly-audienced service token must yield the SVC_ authority");
    }

    // ── Wrong audience → decoder rejects (would be 401) ──────────────────────

    @Test
    void serviceToken_wrongAudience_rejectedByDecoder() throws Exception {
        String token = sign(svcKeys, new JWTClaimsSet.Builder()
                .subject("meeting-ai-service")
                .issuer(SVC_ISSUER)
                .audience("some-other-service") // ← wrong aud
                .claim("perm", List.of(PERM))
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000)));

        // Keycloak decoder rejects on issuer; service decoder rejects on audience.
        assertThrows(JwtException.class, () -> fallbackDecoder.decode(token),
                "a service token with the wrong audience must be rejected by the decoder");
    }

    // ── Keycloak user token w/ perm → decodes but NO SVC_ (end-to-end iss-guard)

    @Test
    void keycloakUserToken_withPermClaim_decodesButConverterWithholdsSvc() throws Exception {
        String token = sign(kcKeys, new JWTClaimsSet.Builder()
                .subject("kc-user-uuid")
                .issuer(KC_ISSUER)
                .audience(AUDIENCE)
                .claim("perm", List.of(PERM)) // even carrying perm…
                .claim("realm_access", Map.of("roles", List.of("admin")))
                .claim("scope", "openid meeting")
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000)));

        Jwt jwt = fallbackDecoder.decode(token); // valid Keycloak token → decodes via first decoder
        Set<String> authorities = authorities(jwt);
        assertFalse(authorities.contains(SVC_WRITE),
                "a Keycloak user token must NOT gain the SVC_ authority even with a perm claim");
        assertTrue(authorities.contains("ROLE_ADMIN"), "user roles must still map");
        assertTrue(authorities.contains("SCOPE_meeting"), "user scopes must still map");
    }

    // ── Forged service issuer without the service key → rejected ─────────────

    @Test
    void forgedServiceIssuer_wrongSigningKey_rejected() throws Exception {
        // Claims say iss=auth-service, but signed with the Keycloak key.
        String token = sign(kcKeys, new JWTClaimsSet.Builder()
                .subject("attacker")
                .issuer(SVC_ISSUER)
                .audience(AUDIENCE)
                .claim("perm", List.of(PERM))
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000)));

        // Keycloak decoder: signature verifies but issuer validator fails.
        // Service decoder: issuer matches but signature verification fails.
        assertThrows(JwtException.class, () -> fallbackDecoder.decode(token),
                "iss=auth-service cannot be forged without the auth-service signing key");
    }

    // ── Config/infra error is NOT masked by the fallback ─────────────────────

    @Test
    void configInitError_fromFirstDecoder_propagates_notMaskedBySecondDecoder() {
        JwtDecoderInitializationException initError = new JwtDecoderInitializationException(
                "JWK set endpoint unreachable", new IllegalStateException("connect timeout"));
        AtomicBoolean secondCalled = new AtomicBoolean(false);

        JwtDecoder failingFirst = t -> {
            throw initError;
        };
        JwtDecoder wouldSucceedSecond = t -> {
            secondCalled.set(true);
            return Jwt.withTokenValue("x").header("alg", "none")
                    .claim("iss", SVC_ISSUER).build();
        };

        FallbackJwtDecoder decoder = new FallbackJwtDecoder(List.of(failingFirst, wouldSucceedSecond));

        JwtDecoderInitializationException thrown = assertThrows(
                JwtDecoderInitializationException.class, () -> decoder.decode("tok"),
                "a JwtDecoderInitializationException must propagate, not fall through");
        assertSame(initError, thrown, "the original init error must be rethrown unchanged");
        assertFalse(secondCalled.get(),
                "a config/infra fault must short-circuit — the second decoder must NOT be tried");
    }

    @Test
    void firstDecoderRejectsOnClaimValidation_fallsBackToSecond() {
        // The core reason FallbackJwtDecoder exists: a service token fails the Keycloak
        // decoder's issuer/audience validator (a JwtValidationException, NOT a signature
        // error), and the chain must then try the service decoder. JwtValidationException
        // extends BadJwtException (Spring 6.5.5), so `catch (BadJwtException)` already
        // covers it — this test pins that behaviour so a future narrowing of the catch
        // clause (e.g. to a sibling type) would break loudly instead of silently killing
        // every service token.
        AtomicBoolean secondCalled = new AtomicBoolean(false);
        Jwt decoded = Jwt.withTokenValue("svc").header("alg", "RS256")
                .claim("iss", SVC_ISSUER).build();

        JwtDecoder keycloakRejectsIssuer = t -> {
            throw new org.springframework.security.oauth2.jwt.JwtValidationException(
                    "iss claim is not equal to the Keycloak issuer",
                    List.of(new org.springframework.security.oauth2.core.OAuth2Error("invalid_token")));
        };
        JwtDecoder serviceDecoder = t -> {
            secondCalled.set(true);
            return decoded;
        };

        FallbackJwtDecoder decoder = new FallbackJwtDecoder(
                List.of(keycloakRejectsIssuer, serviceDecoder));

        assertSame(decoded, decoder.decode("svc"),
                "a JwtValidationException from the first decoder must fall through to the second");
        assertTrue(secondCalled.get(), "the service decoder must be tried after a claim-validation reject");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Set<String> authorities(Jwt jwt) {
        return CONVERTER.convert(jwt).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static String sign(KeyPair keys, JWTClaimsSet.Builder claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-kid").build(),
                claims.build());
        jwt.sign(new RSASSASigner(keys.getPrivate()));
        return jwt.serialize();
    }
}
