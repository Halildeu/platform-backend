package com.example.auth.token;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.auth.config.AuthTokenProperties;

@Service
public class PasswordResetService {

    private final PasswordResetTokenRepository tokenRepository;
    private final AuthTokenProperties tokenProperties;

    public PasswordResetService(PasswordResetTokenRepository tokenRepository,
                                AuthTokenProperties tokenProperties) {
        this.tokenRepository = tokenRepository;
        this.tokenProperties = tokenProperties;
    }

    @Transactional
    public PasswordResetToken issueToken(Long userId, String email) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenProperties.getPasswordResetTtl());

        tokenRepository.deleteByUserId(userId);

        PasswordResetToken token = PasswordResetToken.create(userId, email, now, expiresAt);
        return tokenRepository.save(token);
    }

    @Transactional
    public PasswordResetToken consumeToken(String tokenValue) {
        PasswordResetToken token = tokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new IllegalArgumentException("Şifre sıfırlama bağlantısı geçersiz veya süresi dolmuş."));

        Instant now = Instant.now();

        if (token.isUsed()) {
            throw new IllegalStateException("Şifre sıfırlama bağlantısı zaten kullanılmış.");
        }

        if (token.isExpired(now)) {
            throw new IllegalStateException("Şifre sıfırlama bağlantısının süresi dolmuş.");
        }

        token.markUsed(now);
        return token;
    }
}
