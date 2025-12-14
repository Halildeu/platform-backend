package com.example.apigateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
@Profile({"local", "dev"})
public class SecurityConfigLocal {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityWebFilterChain springSecurityFilterChainLocal(ServerHttpSecurity http) {
        return http
            .cors(cors -> {})
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(ex -> ex
                .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyExchange().permitAll())
            .build();
    }
}
