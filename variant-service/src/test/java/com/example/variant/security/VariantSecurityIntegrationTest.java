package com.example.variant.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.example.commonauth.PermissionCodes;
import com.example.variant.authz.VariantAuthorizationService;
import com.example.variant.authz.AuthzMeResponse;
import com.example.variant.authz.ScopeSummaryDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        classes = {
                com.example.variant.VariantServiceApplication.class,
                VariantSecurityIntegrationTest.JwtTestConfig.class,
                VariantSecurityIntegrationTest.AuthzTestConfig.class
        })
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class VariantSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private StubPermissionServiceAuthzClient permissionServiceAuthzClient;

    @Autowired
    private VariantAuthorizationService variantAuthorizationService;

    @BeforeEach
    void setUpScopes() {
        variantAuthorizationService.clearCache();
        permissionServiceAuthzClient.setResponse(buildAuthzMeResponse(List.of(101L)));
    }

    private String issueToken(String audience, List<String> permissions) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("1")
                .issuer("auth-service")
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("userId", 1)
                .claim("email", "admin@example.com")
                .claim("realm_access", java.util.Map.of("roles", Set.of()))
                .claim("permissions", permissions)
                .build();
        var headers = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }

    private String issueInvalidHsToken(String audience) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("1")
                .issuer("auth-service")
                .audience(List.of(audience))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                .claim("userId", 1)
                .claim("role", "ADMIN")
                .claim("permissions", List.of("VIEW_USERS"))
                .build();
        byte[] secret = "hs256-test-secret-hs256-test-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign HS256 test token", e);
        }
    }

    @Test
    void variants_should_401_without_token() throws Exception {
        mockMvc.perform(get("/api/v1/variants?gridId=101"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void variants_should_200_with_valid_token() throws Exception {
        String token = issueToken("variant-service", List.of(PermissionCodes.VARIANTS_READ));
        mockMvc.perform(get("/api/v1/variants?gridId=101")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void variants_should_403_when_scope_not_allowed() throws Exception {
        permissionServiceAuthzClient.setResponse(buildAuthzMeResponse(List.of(999L)));
        String token = issueToken("variant-service", List.of(PermissionCodes.VARIANTS_READ));
        mockMvc.perform(get("/api/v1/variants?gridId=101")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void variants_should_403_when_permission_missing() throws Exception {
        String token = issueToken("variant-service", List.of("SOME_OTHER_PERMISSION"));
        mockMvc.perform(get("/api/v1/variants?gridId=101")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void variants_should_401_with_audience_mismatch() throws Exception {
        String token = issueToken("user-service", List.of(PermissionCodes.VARIANTS_READ));
        mockMvc.perform(get("/api/v1/variants?gridId=101")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void variants_should_401_with_invalid_signature_alg() throws Exception {
        String token = issueInvalidHsToken("variant-service");
        mockMvc.perform(get("/api/variants?gridId=test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private AuthzMeResponse buildAuthzMeResponse(List<Long> projectIds) {
        AuthzMeResponse response = new AuthzMeResponse();
        response.setUserId("1");
        response.setAllowedScopes(projectIds.stream()
                .map(id -> new ScopeSummaryDto("PROJECT", String.valueOf(id)))
                .toList());
        return response;
    }

    @TestConfiguration
    static class AuthzTestConfig {
        @Bean
        @Primary
        StubPermissionServiceAuthzClient testPermissionServiceAuthzClient() {
            return new StubPermissionServiceAuthzClient();
        }
    }

    static class StubPermissionServiceAuthzClient extends com.example.variant.authz.PermissionServiceAuthzClient {
        private AuthzMeResponse response = new AuthzMeResponse();

        StubPermissionServiceAuthzClient() {
            super(org.springframework.web.reactive.function.client.WebClient.builder());
        }

        @Override
        public AuthzMeResponse getAuthzMe(String bearerToken) {
            return response;
        }

        void setResponse(AuthzMeResponse response) {
            this.response = response;
        }
    }

    @TestConfiguration
    static class JwtTestConfig {
        @Bean
        public RSAKey testRsaKey() throws Exception {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            java.security.KeyPair kp = kpg.generateKeyPair();
            return new RSAKey.Builder((java.security.interfaces.RSAPublicKey) kp.getPublic())
                    .privateKey((java.security.interfaces.RSAPrivateKey) kp.getPrivate())
                    .keyID("test-kid")
                    .build();
        }

        @Bean
        @Primary
        public JwtEncoder testJwtEncoder(RSAKey testRsaKey) {
            JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(testRsaKey));
            return new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(jwkSource);
        }

        @Bean
        @Primary
        public JwtDecoder testJwtDecoder(RSAKey testRsaKey) {
            NimbusJwtDecoder decoder;
            try {
                decoder = NimbusJwtDecoder.withPublicKey(testRsaKey.toRSAPublicKey()).build();
            } catch (com.nimbusds.jose.JOSEException e) {
                throw new RuntimeException(e);
            }
            decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                    org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer("auth-service"),
                    new AudienceValidator("variant-service")
            ));
            return decoder;
        }
    }
}
