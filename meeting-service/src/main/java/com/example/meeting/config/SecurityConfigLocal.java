package com.example.meeting.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Local/dev permitAll chain — no JWT decoding, no OpenFGA. Copied from
 * endpoint-admin-service so local boot needs no Keycloak / OpenFGA.
 */
@Configuration
@Profile({"local", "dev"})
@Order(1)
public class SecurityConfigLocal {

    @Bean
    public SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
