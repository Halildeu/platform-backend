package com.example.user.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@EnableWebSecurity
@Profile({"local", "dev"})
public class SecurityConfigLocal {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain internalServiceSecurityFilterChain(
            HttpSecurity http,
            ServiceTokenAuthenticationFilter serviceTokenAuthenticationFilter
    ) throws Exception {
        http
            .securityMatcher("/api/users/internal/**", "/api/v1/users/internal/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .addFilterBefore(serviceTokenAuthenticationFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    public SecurityFilterChain securityFilterChainLocal(
            HttpSecurity http,
            JwtDecoder jwtDecoder
    ) throws Exception {
        var localDevAnonymousAuthFilter = new LocalDevAnonymousAuthFilter();
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            // Local/dev ortamında request'leri bloklamıyoruz ama Bearer token varsa
            // parse edilip SecurityContext'e taşınmasını istiyoruz.
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder)))
            // JWT yoksa otomatik olarak dev admin kullanicisi ile authenticate et
            // AnonymousAuthenticationFilter'dan SONRA calisir ki anonymous context'i override edebilsin
            .addFilterAfter(localDevAnonymousAuthFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
