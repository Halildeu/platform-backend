package com.example.auditconsumer.bot;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.*;
import static org.assertj.core.api.Assertions.*;

class BotRecordingSecurityTest {
    static final String ISSUER = "https://auth.test/services";
    static final RSAKey KEY;
    static { try { KEY = new RSAKeyGenerator(2048).generate(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }

    @Test void acceptsOnlySignedServiceIdentityWithExactPermission() throws Exception {
        assertThat(decoder().decode(token(b -> {})).getSubject()).isEqualTo("meeting-service");
    }
    @Test void rejectsOtherIssuersAudiencesClientsAndSchedulingOnlyPermission() throws Exception {
        int caseNumber = 0;
        for (Consumer<JWTClaimsSet.Builder> change : List.<Consumer<JWTClaimsSet.Builder>>of(
                b -> b.issuer("https://keycloak.test/realms/test"),
                b -> b.audience("meeting-service"), b -> b.audience((String) null),
                b -> b.subject("teams-capture-worker"), b -> b.claim("client_id", "teams-capture-worker"),
                b -> b.claim("client_id", null).claim("azp", "meeting-service"),
                b -> b.claim("perm", List.of("meeting:teams-schedule:authorize")),
                b -> b.claim("perm", null).claim("roles", List.of("ADMIN")),
                b -> b.claim("perm", BotRecordingSecurity.PERMISSION),
                b -> b.expirationTime(null), b -> b.issueTime(null),
                b -> b.expirationTime(Date.from(Instant.now().minusSeconds(120))))) {
            String signed = token(change);
            assertThat(catchThrowable(() -> decoder().decode(signed))).as("rejection case %s", ++caseNumber).isInstanceOf(JwtException.class);
        }
    }
    @Test void rejectsUntrustedSignature() throws Exception {
        String good = token(b -> {});
        var parsed = SignedJWT.parse(good);
        var jwt = new SignedJWT(parsed.getHeader(), parsed.getJWTClaimsSet());
        jwt.sign(new RSASSASigner(new RSAKeyGenerator(2048).generate()));
        assertThatThrownBy(() -> decoder().decode(jwt.serialize())).isInstanceOf(JwtException.class);
    }
    @Test void disabledOrMissingTrustCannotCreateAnOpenDecoder() {
        var config = new BotRecordingSecurity();
        assertThatThrownBy(() -> config.botRecordingDecoder(new MockEnvironment()).decode("anything")).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> config.botRecordingDecoder(new MockEnvironment().withProperty("audit.bot-recording.enabled", "true")))
                .isInstanceOf(IllegalStateException.class);
    }
    static NimbusJwtDecoder decoder() throws Exception {
        var decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
        BotRecordingSecurity.configure(decoder, ISSUER);
        return decoder;
    }
    static String token(Consumer<JWTClaimsSet.Builder> change) throws Exception {
        var b = new JWTClaimsSet.Builder().issuer(ISSUER).subject("meeting-service")
                .audience(BotRecordingSecurity.AUDIENCE).claim("client_id", "meeting-service")
                .claim("perm", List.of(BotRecordingSecurity.PERMISSION))
                .issueTime(Date.from(Instant.now().minusSeconds(1))).expirationTime(Date.from(Instant.now().plusSeconds(300)));
        change.accept(b);
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), b.build());
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }
}
