package com.example.auth.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import com.example.auth.config.AuthTokenProperties;
import com.example.auth.token.EmailVerificationToken;
import com.example.auth.token.PasswordResetToken;

@Service
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final AuthTokenProperties tokenProperties;

    public EmailNotificationService(AuthTokenProperties tokenProperties) {
        this.tokenProperties = tokenProperties;
    }

    public void sendVerificationEmail(EmailVerificationToken token) {
        String link = UriComponentsBuilder.fromHttpUrl(resolveBaseUrl(tokenProperties.getVerificationBaseUrl(), "http://localhost:3000/verify-email"))
                .queryParam("token", token.getToken())
                .toUriString();

        log.info("E-posta doğrulama bağlantısı gönderiliyor -> email={}, link={}", token.getEmail(), link);
        // TODO: mail-service / notification-service entegrasyonu eklendiğinde burada HTTP çağrısı yapılacak.
    }

    public void sendPasswordResetEmail(PasswordResetToken token) {
        String link = UriComponentsBuilder.fromHttpUrl(resolveBaseUrl(tokenProperties.getResetBaseUrl(), "http://localhost:3000/reset-password"))
                .queryParam("token", token.getToken())
                .toUriString();

        log.info("Şifre sıfırlama bağlantısı gönderiliyor -> email={}, link={}", token.getEmail(), link);
        // TODO: mail-service / notification-service entegrasyonu eklendiğinde burada HTTP çağrısı yapılacak.
    }

    private String resolveBaseUrl(String configured, String fallback) {
        return (configured == null || configured.isBlank()) ? fallback : configured;
    }
}
