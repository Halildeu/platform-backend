package com.example.user.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;

@Entity
@Table(name = "users")
public class User implements UserDetails { // UserDetails arayüzünü uygular

    public static final int DEFAULT_SESSION_TIMEOUT_MINUTES = 15;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required")
    @Column(name = "name", nullable = false)
    private String name;

    @Email(message = "Email must be valid")
    @NotBlank(message = "Email is required")
    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @NotBlank(message = "Password is required")
    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "role", nullable = false)
    private String role = "USER";

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "create_date", updatable = false)
    private LocalDateTime createDate = LocalDateTime.now();

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    @Column(name = "session_timeout_minutes", columnDefinition = "integer default " + DEFAULT_SESSION_TIMEOUT_MINUTES + " not null")
    private Integer sessionTimeoutMinutes = DEFAULT_SESSION_TIMEOUT_MINUTES;

    // --- Constructorlar ---
    public User() {}

    // Gerekirse diğer constructor'larınız burada kalabilir

    // --- Getter & Setter Metotları (Mevcut olanlar) ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public void setPassword(String password) { this.password = password; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = normalizeRole(role); }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreateDate() { return createDate; }
    public void setCreateDate(LocalDateTime createDate) { this.createDate = createDate; }
    public LocalDateTime getLastLogin() { return lastLogin; }
    public void setLastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; }
    public Integer getSessionTimeoutMinutes() { return sessionTimeoutMinutes; }
    public void setSessionTimeoutMinutes(Integer sessionTimeoutMinutes) { this.sessionTimeoutMinutes = sessionTimeoutMinutes; }

    // --- UserDetails Arayüzünden Gelen Zorunlu Metotlar ---

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Kullanıcının rolünü Spring Security'nin anladığı formata çevirir.
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + this.role));
    }

    @Override
    public String getPassword() {
        // UserDetails'in istediği şifreyi döndürür.
        return this.password;
    }

    @Override
    public String getUsername() {
        // UserDetails'in istediği kullanıcı adını döndürür. Biz email kullanıyoruz.
        return this.email;
    }

    @Override
    public boolean isAccountNonExpired() {
        // Hesabın süresinin dolup dolmadığını kontrol eder. Şimdilik true bırakabiliriz.
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        // Hesabın kilitli olup olmadığını kontrol eder. Şimdilik true bırakabiliriz.
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        // Parolanın süresinin dolup dolmadığını kontrol eder. Şimdilik true bırakabiliriz.
        return true;
    }

    @Override
    public boolean isEnabled() {
        // Hesabın aktif olup olmadığını veritabanındaki 'enabled' alanından alır.
        return this.enabled;
    }

    @PrePersist
    @PreUpdate
    private void ensureSessionTimeout() {
        if (this.sessionTimeoutMinutes == null || this.sessionTimeoutMinutes < 1) {
            this.sessionTimeoutMinutes = DEFAULT_SESSION_TIMEOUT_MINUTES;
        }
        this.role = normalizeRole(this.role);
    }

    public static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "USER";
        }
        String trimmed = role.trim().toUpperCase();
        if (trimmed.startsWith("ROLE_")) {
            trimmed = trimmed.substring(5);
        }
        return trimmed.isBlank() ? "USER" : trimmed;
    }
}
