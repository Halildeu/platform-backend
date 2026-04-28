package com.example.permission.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.permission.service.AuthenticatedUserLookupService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnBean(ScopeFilterInterceptor.class)
public class WebMvcConfig implements WebMvcConfigurer {

    private final ScopeFilterInterceptor scopeFilterInterceptor;
    private final OpenFgaAuthzService authzService;
    private final AuthenticatedUserLookupService userLookupService;

    public WebMvcConfig(ScopeFilterInterceptor scopeFilterInterceptor,
                        OpenFgaAuthzService authzService,
                        @Nullable AuthenticatedUserLookupService userLookupService) {
        this.scopeFilterInterceptor = scopeFilterInterceptor;
        this.authzService = authzService;
        this.userLookupService = userLookupService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ADR-0012 Phase 3: @RequireModule → OpenFGA check (replaces @PreAuthorize).
        // 2026-04-29 fix: numeric user ID resolution via AuthenticatedUserLookupService
        // matches /api/v1/authz/me path so tuples seeded with numeric IDs (user:1204)
        // are resolved correctly. Relation alias mapping (viewer→can_view etc.) lives
        // inside the interceptor.
        registry.addInterceptor(new RequireModuleInterceptor(authzService, userLookupService))
                .addPathPatterns("/api/**");
        registry.addInterceptor(scopeFilterInterceptor)
                .addPathPatterns("/api/**");
    }
}
