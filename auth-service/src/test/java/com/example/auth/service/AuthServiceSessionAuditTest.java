package com.example.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Collections;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.example.auth.config.JwtProperties;
import com.example.auth.dto.JwtResponse;
import com.example.auth.notification.EmailNotificationService;
import com.example.auth.permission.PermissionAuditMirrorClient;
import com.example.auth.permission.PermissionServiceClient;
import com.example.auth.security.AuthenticatedUserDetails;
import com.example.auth.security.JwtTokenProvider;
import com.example.auth.token.EmailVerificationService;
import com.example.auth.token.PasswordResetService;
import com.example.auth.user.UserServiceClient;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

@ExtendWith(MockitoExtension.class)
class AuthServiceSessionAuditTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private PermissionServiceClient permissionServiceClient;

    @Mock
    private UserServiceClient userServiceClient;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private PasswordResetService passwordResetService;

    @Mock
    private EmailNotificationService emailNotificationService;

    @Mock
    private AuthAuditService authAuditService;

    @Mock
    private PermissionAuditMirrorClient permissionAuditMirrorClient;

    private AuthService authService;
    private AuthenticatedUserDetails userDetails;
    private RSAKey testRsaKey;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-secret-32-characters-min-length!!!!");
        jwtProperties.setExpiration(3_600_000L);
        jwtProperties.setIssuer("test-issuer");

        initTestRsaKey();
        JwtEncoder encoder = buildTestEncoder();
        JwtDecoder decoder = buildTestDecoder();
        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(jwtProperties, encoder, decoder);

        authService = new AuthService(
                authenticationManager,
                jwtTokenProvider,
                jwtProperties,
                permissionServiceClient,
                userServiceClient,
                emailVerificationService,
                passwordResetService,
                emailNotificationService,
                authAuditService,
                false
        );

        userDetails = new AuthenticatedUserDetails(
                99L,
                "test@example.com",
                "password",
                true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")),
                15
        );
    }

    @Test
    void login_recordsSessionAuditOnSuccessfulAuthentication() {
        Authentication successfulAuthentication = mock(Authentication.class);
        when(successfulAuthentication.getPrincipal()).thenReturn(userDetails);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(successfulAuthentication);
        when(permissionServiceClient.getPermissions(99L, 42L)).thenReturn(Set.of("audit-read"));

        JwtResponse jwtResponse = authService.login("test@example.com", "password", 42L);

        assertNotNull(jwtResponse);
        assertFalse(jwtResponse.getToken().isBlank());
        assertEquals("test@example.com", jwtResponse.getEmail());
        verify(authAuditService).recordSessionCreated(
                99L,
                "test@example.com",
                42L,
                "USER",
                Set.of("audit-read"),
                15,
                jwtResponse.getExpiresAt()
        );
    }

    @Test
    void login_doesNotRecordAuditWhenAuthenticationFails() {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new org.springframework.security.core.AuthenticationException("Bad credentials") { });

        assertThrows(org.springframework.security.core.AuthenticationException.class,
                () -> authService.login("test@example.com", "wrong_password", 42L));

        verify(authAuditService, never()).recordSessionCreated(
                any(),
                any(),
                any(),
                any(),
                any(),
                anyInt(),
                anyLong()
        );
    }

    private void initTestRsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            testRsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((java.security.interfaces.RSAPrivateKey) keyPair.getPrivate())
                    .keyID("test-key")
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("Test RSA key üretilemedi", ex);
        }
    }

    private JwtEncoder buildTestEncoder() {
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(testRsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    private JwtDecoder buildTestDecoder() {
        try {
            return NimbusJwtDecoder.withPublicKey(testRsaKey.toRSAPublicKey()).build();
        } catch (Exception ex) {
            throw new IllegalStateException("Test RSA decoder üretilemedi", ex);
        }
    }
}
