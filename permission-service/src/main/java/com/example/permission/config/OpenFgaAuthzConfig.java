package com.example.permission.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import org.springframework.beans.factory.ObjectProvider;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.commonauth.scope.AuthzVersionProvider;
import com.example.commonauth.scope.OpenFgaScopeReader;
import com.example.commonauth.scope.ScopeContextCache;
import com.example.permission.service.AuthzVersionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenFGA beans that are only meaningful when enforcement is ON.
 *
 * <p>#933: {@code openFgaProperties} and {@code openFgaAuthzService} moved OUT of this class
 * into the unconditional {@link OpenFgaAuthzBaseConfig}, because {@link WebMvcConfig} hard-
 * requires the authz service and this class disappears when the flag is off — which crashed
 * the context in permission-service's own default configuration
 * ({@code application.properties} defaults {@code erp.openfga.enabled} to {@code false}).
 *
 * <p>The beans below stay conditional on purpose: {@code authzVersionProvider} needs
 * {@code AuthzVersionService}, which carries the same {@code @ConditionalOnProperty}, so
 * making this class unconditional would only move the crash one bean over.
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "erp.openfga.enabled", havingValue = "true", matchIfMissing = false)
public class OpenFgaAuthzConfig {

    @Bean
    public ScopeContextCache scopeContextCache(
            @Value("${scope.cache.enabled:true}") boolean enabled,
            @Value("${scope.cache.ttl-seconds:30}") int ttlSeconds,
            @Value("${scope.cache.ttl-jitter-seconds:3}") int jitterSeconds,
            @Value("${scope.cache.max-size:5000}") long maxSize) {
        return new ScopeContextCache(java.time.Duration.ofSeconds(ttlSeconds), java.time.Duration.ofSeconds(jitterSeconds), maxSize, enabled);
    }

    @Bean
    public AuthzVersionProvider authzVersionProvider(AuthzVersionService authzVersionService) {
        return authzVersionService::getCurrentVersion;
    }

    /**
     * Codex thread 019e0891 iter-2 AGREE absorb (PR-BE-10 Phase 3+4):
     * shared OpenFGA scope reader bean. Used by both ScopeContextFilter
     * (request-scope binding) and admin-side services
     * (UserScopeService, AuthorizationQueryService) to read scope tuples
     * from OpenFGA via the same parallel-fetch + cache + relation map
     * path. Aligns reads with the canonical Faz 21.3 ADR-0008
     * explicit-scope model (all object types use {@code viewer}).
     */
    @Bean
    public OpenFgaScopeReader openFgaScopeReader(OpenFgaAuthzService authzService,
                                                  OpenFgaProperties properties,
                                                  ObjectProvider<ScopeContextCache> cacheProvider,
                                                  ObjectProvider<AuthzVersionProvider> versionProvider) {
        return new OpenFgaScopeReader(
                authzService,
                properties,
                cacheProvider.getIfAvailable(),
                versionProvider.getIfAvailable());
    }
}
