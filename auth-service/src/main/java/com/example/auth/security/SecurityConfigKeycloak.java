package com.example.auth.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@Profile({"!local", "!dev"})
public class SecurityConfigKeycloak {

    @Bean
    public SecurityFilterChain securityFilterChainKeycloak(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll()
                )
                .oauth2ResourceServer(oauth -> oauth
                        .jwt()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setCharacterEncoding("UTF-8");
                            response.setContentType("application/json");
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.getWriter().write("{\"error\": \"Bu kaynağa erişmek için kimlik doğrulaması gereklidir.\"}");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setCharacterEncoding("UTF-8");
                            response.setContentType("application/json");
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.getWriter().write("{\"error\": \"Bu kaynağa erişim yetkiniz bulunmuyor.\"}");
                        })
                );

        return http.build();
    }
}
