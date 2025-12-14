package com.example.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    /**
     * Bu metot, Spring'e projenin tamamında kullanılacak olan
     * PasswordEncoder nesnesini nasıl üreteceğini söyler.
     * Biz, güvenli bir şifreleme algoritması olan BCrypt'i kullanıyoruz.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
