package com.example.auth.token;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 191)
    private String email;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant usedAt;

    protected PasswordResetToken() {
        // JPA
    }

    private PasswordResetToken(Long userId, String email, Instant createdAt, Instant expiresAt, String token) {
        this.userId = userId;
        this.email = email;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.token = token;
    }

    public static PasswordResetToken create(Long userId, String email, Instant createdAt, Instant expiresAt) {
        String token = UUID.randomUUID().toString().replace("-", "");
        return new PasswordResetToken(userId, email, createdAt, expiresAt, token);
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public void markUsed(Instant at) {
        this.usedAt = at;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getToken() {
        return token;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
