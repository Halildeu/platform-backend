package com.example.apigateway;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Authoritative CORS handler for non-local profiles (prod, staging, docker, test).
 *
 * Background (P1.5, Codex CNS thread 019d9b43):
 *   Spring Cloud Gateway's globalcors property config routes through
 *   SimpleUrlHandlerMapping. Even with `add-to-simple-url-handler-mapping=true`
 *   (added in PR F / Codex Tur 12), OPTIONS preflight requests with an Origin
 *   header were still returning 403 in staging — the property config is
 *   consumed too late in the reactive filter chain for preflight short-circuit.
 *
 *   An @Order(HIGHEST_PRECEDENCE) CorsWebFilter bean runs ahead of Spring
 *   Cloud Gateway's route mapping and handles the preflight deterministically.
 *   {@link LocalDevCorsConfig} already provides this for local/dev; ProdCorsConfig
 *   covers every other profile and is the non-negotiable prod CORS surface.
 *
 * Security:
 *   - Exact origin allowlist (no wildcard) because allowCredentials=true.
 *   - Explicit header allowlist instead of `*` (same reason — wildcard + credentials
 *     is blocked by CORS spec; Chrome 104+ rejects the response).
 *   - Max-age 3600 so browsers don't re-preflight every request.
 *
 * Source of truth for allowed origins:
 *   `gateway.cors.allowed-origins` property (alias of `GATEWAY_CORS_ALLOWED_ORIGINS`
 *   env var via Spring relaxed binding). Same value feeds Spring Cloud Gateway's
 *   globalcors config in application.properties so there is no drift between
 *   the two CORS layers.
 */
@Configuration
@Profile("!local & !dev")
public class ProdCorsConfig {

    @Value("${gateway.cors.allowed-origins:https://ai.acik.com}")
    private String allowedOriginsRaw;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsWebFilter prodCorsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        // Header allowlist mirrors the ones actually sent by the web bundle:
        //   - Authorization / Content-Type / Accept: standard axios / fetch
        //   - Cache-Control: mfe-audit live stream (see useAuditLiveStream.ts)
        //   - X-Trace-Id / X-Correlation-Id: shared-http request tagging
        //   - traceparent / tracestate: W3C Trace Context (mfe-shell otel.ts)
        //   - X-Company-Id / X-Project-Id / X-Warehouse-Id: scope headers
        //     (mfe-users, core-data filtering)
        //   - X-Internal-Api-Key: grid variants service calls (optional)
        // Wildcard `*` is not safe with allowCredentials=true (CORS spec / Chrome 104+
        // reject the response), so the list must be enumerated.
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "Cache-Control",
                "X-Trace-Id",
                "X-Correlation-Id",
                "traceparent",
                "tracestate",
                "X-Company-Id",
                "X-Project-Id",
                "X-Warehouse-Id",
                "X-Internal-Api-Key"
        ));
        config.setExposedHeaders(List.of("X-Trace-Id", "X-Correlation-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/manifest/**", config);
        return new CorsWebFilter(source);
    }
}
