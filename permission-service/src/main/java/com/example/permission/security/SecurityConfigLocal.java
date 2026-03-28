package com.example.permission.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Local/dev ortaminda JWT olmadan gelen istekleri kabul eder.
 * Frontend permitAll modunda calisirken backend API'lari da calisir.
 */
@Configuration
@EnableWebSecurity
@Profile({"local", "dev"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityConfigLocal {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfigLocal.class);

    @Bean
    public SecurityFilterChain securityFilterChainLocal(HttpSecurity http) throws Exception {
        var autoAuthFilter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(@NonNull HttpServletRequest request,
                                            @NonNull HttpServletResponse response,
                                            @NonNull FilterChain filterChain) throws ServletException, IOException {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
                    Jwt fakeJwt = new Jwt(
                        "local-dev-token",
                        Instant.now(),
                        Instant.now().plusSeconds(3600),
                        Map.of("alg", "none"),
                        Map.of(
                            "sub", "local-dev",
                            "email", "admin@serban.dev",
                            "preferred_username", "admin@serban.dev"
                        )
                    );
                    var authentication = new UsernamePasswordAuthenticationToken(
                        fakeJwt, null,
                        List.of(
                            new SimpleGrantedAuthority("ROLE_ADMIN"),
                            new SimpleGrantedAuthority("ADMIN"),
                            new SimpleGrantedAuthority("user-read"),
                            new SimpleGrantedAuthority("user-update"),
                            new SimpleGrantedAuthority("access-read"),
                            new SimpleGrantedAuthority("access-update"),
                            new SimpleGrantedAuthority("audit-read"),
                            new SimpleGrantedAuthority("REPORT_VIEW"),
                            new SimpleGrantedAuthority("REPORT_EXPORT"),
                            new SimpleGrantedAuthority("REPORT_MANAGE")
                        )
                    );
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    log.info("[local-dev] Auto-authenticated: {}", request.getRequestURI());
                }
                filterChain.doFilter(request, response);
            }
        };

        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .addFilterAfter(autoAuthFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
