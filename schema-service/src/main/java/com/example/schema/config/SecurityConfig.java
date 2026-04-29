package com.example.schema.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                    // Codex 019dda1c iter-29: master-data internal endpoint
                    // is gateway-private and protected by an API-key check
                    // inside the controller (X-Internal-Api-Key vs the
                    // schema.master-data.internal-api-key property). Spring
                    // Security must not reject it before the controller
                    // runs — exempt the path here. The endpoint is NOT
                    // surfaced through the public gateway route, so this
                    // permitAll only applies to in-cluster service-to-
                    // service calls (permission-service → schema-service).
                    .requestMatchers("/api/v1/schema/master-data/**").permitAll()
                    // Codex 019dda1c iter-30d: explicit diagnostic path. Spring
                    // Boot 3.x MvcRequestMatcher seems to not extend the
                    // /master-data/** glob to /master-data/diagnostic/** in
                    // some startup orderings (smoke returned 401 with the
                    // single pattern even though the diagnostic controller is
                    // registered). Adding the explicit pattern as a belt-and-
                    // braces second match ensures the diagnostic endpoint is
                    // reachable when the parent glob isn't applied.
                    .requestMatchers("/api/v1/schema/master-data/diagnostic/**").permitAll()
                    .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));

        return http.build();
    }

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:3008}")
    private List<String> allowedOrigins;

    private CorsConfigurationSource corsSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
