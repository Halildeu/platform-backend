package com.example.auth.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@org.springframework.context.annotation.Profile({"local","dev"})
public class SecurityConfig {

    // SADECE JwtAuthFilter'ı enjekte ediyoruz.
    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // .authenticationProvider() satırını buradan kaldırdık.

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**",
                                "/health",
                                "/actuator/health",
                                "/oauth2/**",
                                "/.well-known/jwks.json").permitAll()
                        .requestMatchers("/api/variants/**").authenticated()
                        .anyRequest().authenticated()
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
        
        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
                
        return http.build();
    }
}
