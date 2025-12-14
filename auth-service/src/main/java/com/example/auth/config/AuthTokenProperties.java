package com.example.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "auth.tokens")
public class AuthTokenProperties {

    /**
     * Tokenlerin oluşturulacağı doğrulama bağlantısının temel URL'si.
     * Örn: https://app.example.com/verify-email
     */
    private String verificationBaseUrl;

    /**
     * Şifre sıfırlama bağlantısının temel URL'si.
     * Örn: https://app.example.com/reset-password
     */
    private String resetBaseUrl;

    private Duration verificationTtl = Duration.ofHours(24);
    private Duration passwordResetTtl = Duration.ofHours(1);

    public String getVerificationBaseUrl() {
        return verificationBaseUrl;
    }

    public void setVerificationBaseUrl(String verificationBaseUrl) {
        this.verificationBaseUrl = verificationBaseUrl;
    }

    public String getResetBaseUrl() {
        return resetBaseUrl;
    }

    public void setResetBaseUrl(String resetBaseUrl) {
        this.resetBaseUrl = resetBaseUrl;
    }

    public Duration getVerificationTtl() {
        return verificationTtl;
    }

    public void setVerificationTtl(Duration verificationTtl) {
        this.verificationTtl = verificationTtl;
    }

    public Duration getPasswordResetTtl() {
        return passwordResetTtl;
    }

    public void setPasswordResetTtl(Duration passwordResetTtl) {
        this.passwordResetTtl = passwordResetTtl;
    }
}
