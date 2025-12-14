package com.example.auth.token;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {

    Optional<EmailVerificationToken> findByToken(String token);

    void deleteByUserId(Long userId);
}
