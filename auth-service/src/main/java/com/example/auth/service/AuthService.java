package com.example.auth.service;

import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import com.example.auth.dto.ForgotPasswordRequest;
import com.example.auth.dto.JwtResponse;
import com.example.auth.dto.MessageResponse;
import com.example.auth.dto.RegisterRequest;
import com.example.auth.dto.ResetPasswordRequest;
import com.example.auth.config.JwtProperties;
import com.example.auth.notification.EmailNotificationService;
import com.example.auth.permission.PermissionServiceClient;
import com.example.auth.security.AuthenticatedUserDetails;
import com.example.auth.security.JwtTokenProvider;
import com.example.auth.token.EmailVerificationService;
import com.example.auth.token.PasswordResetService;
import com.example.auth.token.EmailVerificationToken;
import com.example.auth.token.PasswordResetToken;
import com.example.auth.user.InternalUserResponse;
import com.example.auth.user.RemoteUserResponse;
import com.example.auth.user.UserServiceClient;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final PermissionServiceClient permissionServiceClient;
    private final UserServiceClient userServiceClient;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;
    private final EmailNotificationService emailNotificationService;
    private final AuthAuditService authAuditService;
    private final boolean adminFallbackEnabled;

    public AuthService(AuthenticationManager authenticationManager,
                       JwtTokenProvider jwtTokenProvider,
                       JwtProperties jwtProperties,
                       PermissionServiceClient permissionServiceClient,
                       UserServiceClient userServiceClient,
                       EmailVerificationService emailVerificationService,
                       PasswordResetService passwordResetService,
                       EmailNotificationService emailNotificationService,
                       AuthAuditService authAuditService,
                       @Value("${security.admin-fallback.enabled:false}") boolean adminFallbackEnabled) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtProperties = jwtProperties;
        this.permissionServiceClient = permissionServiceClient;
        this.userServiceClient = userServiceClient;
        this.emailVerificationService = emailVerificationService;
        this.passwordResetService = passwordResetService;
        this.emailNotificationService = emailNotificationService;
        this.authAuditService = authAuditService;
        this.adminFallbackEnabled = adminFallbackEnabled;
    }

    public JwtResponse login(String email, String password, Long companyId) throws AuthenticationException {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password)
        );

        AuthenticatedUserDetails userDetails = (AuthenticatedUserDetails) authentication.getPrincipal();

        String role = userDetails.getAuthorities().stream()
                .findFirst()
                .map(grantedAuthority -> grantedAuthority.getAuthority().replace("ROLE_", ""))
                .orElse("USER");

        Set<String> permissions = permissionServiceClient.getPermissions(userDetails.getId(), companyId);
        if ((permissions == null || permissions.isEmpty()) && "ADMIN".equalsIgnoreCase(role) && adminFallbackEnabled) {
            // Geliştirme kolaylığı: yalnızca admin-fallback özelliği açıkken dev/profiller için varsayılan yetkiler eklenir.
            permissions = java.util.Set.of(
                    "VIEW_USERS",
                    "MANAGE_USERS",
                    "VIEW_ACCESS",
                    "VIEW_AUDIT",
                    "APPROVE_PURCHASE",
                    "MANAGE_WAREHOUSE"
            );
            log.warn("PermissionService boş döndü; ADMIN için varsayılan yetkiler eklendi (dev fallback, security.admin-fallback.enabled=true)");
        }

        long configuredDefaultMinutes = Math.max(1, Math.round(Math.ceil(jwtProperties.getExpiration() / 60000.0)));
        int maxAllowedTimeout = configuredDefaultMinutes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) configuredDefaultMinutes;

        int requestedTimeout = Optional.ofNullable(userDetails.getSessionTimeoutMinutes()).orElse(0);
        if (requestedTimeout < 1) {
            requestedTimeout = maxAllowedTimeout;
        }

        int sessionTimeoutMinutes = Math.max(1, Math.min(requestedTimeout, maxAllowedTimeout));

        String token = jwtTokenProvider.generateToken(userDetails, role, permissions, companyId, sessionTimeoutMinutes);

        try {
            userServiceClient.updateLastLogin(userDetails.getId());
        } catch (Exception ex) {
            log.warn("Kullanıcı son giriş tarihi güncellenemedi. userId={}", userDetails.getId(), ex);
        }

        long expiresAt = System.currentTimeMillis() + sessionTimeoutMinutes * 60_000L;

        try {
            authAuditService.recordSessionCreated(
                    userDetails.getId(),
                    userDetails.getUsername(),
                    companyId,
                    role,
                    permissions,
                    sessionTimeoutMinutes,
                    expiresAt
            );
        } catch (Exception ex) {
            log.warn("Session audit olayı yazılamadı. userId={}", userDetails.getId(), ex);
        }

        return new JwtResponse(token, userDetails.getUsername(), role, permissions, expiresAt, (int) sessionTimeoutMinutes);
    }

    public RegistrationResult registerDetailed(RegisterRequest request) {
        RemoteUserResponse registeredUser = userServiceClient.registerPublicUser(request);
        if (registeredUser == null || registeredUser.getId() == null) {
            throw new IllegalStateException("Kullanıcı kaydı tamamlanamadı. Lütfen daha sonra tekrar deneyin.");
        }

        EmailVerificationToken token = emailVerificationService.issueToken(
                registeredUser.getId(),
                registeredUser.getEmail()
        );
        emailNotificationService.sendVerificationEmail(token);

        return new RegistrationResult(
                registeredUser.getId(),
                registeredUser.getEmail(),
                "PENDING_VERIFICATION",
                "Kayıt talebiniz alındı. Lütfen e-posta adresinizi doğrulayın.");
    }

    public MessageResponse register(RegisterRequest request) {
        RegistrationResult result = registerDetailed(request);
        return new MessageResponse(result.message());
    }

    public MessageResponse verifyEmail(String tokenValue) {
        EmailVerificationToken token = emailVerificationService.consumeToken(tokenValue);
        userServiceClient.activateUser(token.getUserId());

        return new MessageResponse("E-posta adresiniz başarıyla doğrulandı. Giriş yapabilirsiniz.");
    }

    public MessageResponse forgotPassword(ForgotPasswordRequest request) {
        Optional<InternalUserResponse> userOpt = userServiceClient.findInternalUserByEmail(request.getEmail());
        if (userOpt.isEmpty()) {
            // Bilgi vermeden başarı dönüyoruz (güvenlik amaçlı).
            return new MessageResponse("Eğer bu e-posta adresi kayıtlıysa, şifre sıfırlama bağlantısı gönderildi.");
        }

        InternalUserResponse user = userOpt.get();
        PasswordResetToken token = passwordResetService.issueToken(user.getId(), user.getEmail());
        emailNotificationService.sendPasswordResetEmail(token);

        return new MessageResponse("Eğer bu e-posta adresi kayıtlıysa, şifre sıfırlama bağlantısı gönderildi.");
    }

    public MessageResponse resetPassword(ResetPasswordRequest request) {
        PasswordResetToken token = passwordResetService.consumeToken(request.getToken());
        userServiceClient.updatePassword(token.getUserId(), request.getNewPassword());

        return new MessageResponse("Şifreniz başarıyla güncellendi.");
    }

    public static class RegistrationResult {
        private final Long userId;
        private final String email;
        private final String status;
        private final String message;

        public RegistrationResult(Long userId, String email, String status, String message) {
            this.userId = userId;
            this.email = email;
            this.status = status;
            this.message = message;
        }

        public Long userId() {
            return userId;
        }

        public String email() {
            return email;
        }

        public String status() {
            return status;
        }

        public String message() {
            return message;
        }
    }
}
