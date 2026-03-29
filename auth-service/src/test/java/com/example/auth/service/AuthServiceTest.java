package com.example.auth.service;

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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.core.userdetails.UserDetails;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    // @Mock: Bu bağımlılıkların sahte versiyonları oluşturulacak.
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
    private JwtTokenProvider jwtTokenProvider;
    private JwtProperties jwtProperties;
    private RSAKey testRsaKey;

    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-secret-32-characters-min-length!!!!");
        jwtProperties.setExpiration(3600_000L);
        jwtProperties.setIssuer("test-issuer");

        initTestRsaKey();
        JwtEncoder encoder = buildTestEncoder();
        JwtDecoder decoder = buildTestDecoder();
        jwtTokenProvider = new JwtTokenProvider(jwtProperties, encoder, decoder);

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

        // Testler için standart bir UserDetails nesnesi hazırlıyoruz.
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
    void login_WithCorrectCredentials_ShouldReturnJwtResponse() {
        // --- ARRANGE (Hazırlık) ---
        // Sahte bir 'Authentication' nesnesi oluşturuyoruz.
        // Gerçekte bu nesne, başarılı bir kimlik doğrulamasından sonra AuthenticationManager tarafından döndürülür.
        Authentication successfulAuthentication = mock(Authentication.class);
        when(successfulAuthentication.getPrincipal()).thenReturn(userDetails);

        // Mockito'ya, authenticationManager.authenticate metodu herhangi bir token ile çağrıldığında,
        // hazırladığımız bu başarılı 'Authentication' nesnesini döndürmesini söylüyoruz.
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(successfulAuthentication);

        // --- ACT (Eylem) ---
        // Test edeceğimiz asıl login metodunu çağırıyoruz.
        when(permissionServiceClient.getPermissions(99L, 1L)).thenReturn(Collections.singleton("VIEW_USERS"));

        JwtResponse jwtResponse = authService.login("test@example.com", "password", 1L);

        // --- ASSERT (Doğrulama) ---
        // Dönen sonucun beklediğimiz gibi olup olmadığını kontrol ediyoruz.
        assertNotNull(jwtResponse);
        assertFalse(jwtResponse.getToken().isBlank());
        assertEquals("test@example.com", jwtResponse.getEmail());
        assertEquals("USER", jwtResponse.getRole());
        assertTrue(jwtResponse.getPermissions().contains("VIEW_USERS"));
        System.out.println(">>> Başarılı login testi geçti!");
    }

    @Test
    void login_WithIncorrectCredentials_ShouldThrowAuthenticationException() {
        // --- ARRANGE (Hazırlık) ---
        // Bu senaryoda, kimlik doğrulamanın başarısız olduğunu varsayıyoruz.
        // Mockito'ya, authenticate metodu çağrıldığında bir AuthenticationException fırlatmasını söylüyoruz.
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new AuthenticationException("Bad credentials") {});

        // --- ACT & ASSERT (Eylem ve Doğrulama) ---
        // authService.login metodunun bir AuthenticationException fırlattığını doğruluyoruz.
        assertThrows(AuthenticationException.class, () -> {
            authService.login("test@example.com", "wrong_password", 1L);
        });
        System.out.println(">>> Başarısız login (hatalı şifre) testi geçti!");
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
