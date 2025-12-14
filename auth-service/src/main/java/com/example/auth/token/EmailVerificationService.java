package com.example.auth.token;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.auth.config.AuthTokenProperties;

@Service
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokenRepository;
    private final AuthTokenProperties tokenProperties;

    public EmailVerificationService(EmailVerificationTokenRepository tokenRepository,
                                    AuthTokenProperties tokenProperties) {
        this.tokenRepository = tokenRepository;
        this.tokenProperties = tokenProperties;
    }

    @Transactional
    public EmailVerificationToken issueToken(Long userId, String email) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenProperties.getVerificationTtl());

        // Eski tokenları temizle
        tokenRepository.deleteByUserId(userId);

        EmailVerificationToken token = EmailVerificationToken.create(userId, email, now, expiresAt);
        return tokenRepository.save(token);
    }

    @Transactional
    public EmailVerificationToken consumeToken(String tokenValue) {
        EmailVerificationToken token = tokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new IllegalArgumentException("Doğrulama bağlantısı geçersiz veya süresi dolmuş."));

        Instant now = Instant.now();

        if (token.isConsumed()) {
            throw new IllegalStateException("Doğrulama bağlantısı zaten kullanılmış.");
        }

        if (token.isExpired(now)) {
            throw new IllegalStateException("Doğrulama bağlantısının süresi dolmuş.");
        }

        token.markConfirmed(now);
        return token;
    }
}
