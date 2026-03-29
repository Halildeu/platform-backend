package com.example.coredata.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Local/dev security config — permits all requests without JWT validation.
 * Activated when spring.profiles.active includes "local" or "dev".
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
