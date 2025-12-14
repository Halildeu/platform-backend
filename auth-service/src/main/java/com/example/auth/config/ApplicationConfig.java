package com.example.auth.config;

import com.example.auth.security.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class ApplicationConfig {

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    // Gerekli servisleri ve şifreleyiciyi constructor ile enjekte ediyoruz.
    public ApplicationConfig(CustomUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Bu bean, Spring Security'e kimlik doğrulamanın nasıl yapılacağını söyler.
     * Hangi UserDetailsService'i ve hangi PasswordEncoder'ı kullanacağını belirtir.
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService); // Kullanıcıyı nasıl bulacağını söylüyoruz.
        authProvider.setPasswordEncoder(passwordEncoder);       // Şifreyi nasıl kontrol edeceğini söylüyoruz.
        return authProvider;
    }
}