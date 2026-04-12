package com.example.permission.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.permission.config.RequireModuleInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnBean(ScopeFilterInterceptor.class)
public class WebMvcConfig implements WebMvcConfigurer {

    private final ScopeFilterInterceptor scopeFilterInterceptor;
    private final OpenFgaAuthzService authzService;

    public WebMvcConfig(ScopeFilterInterceptor scopeFilterInterceptor,
                        OpenFgaAuthzService authzService) {
        this.scopeFilterInterceptor = scopeFilterInterceptor;
        this.authzService = authzService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ADR-0012 Phase 3: @RequireModule → OpenFGA check (replaces @PreAuthorize)
        registry.addInterceptor(new RequireModuleInterceptor(authzService))
                .addPathPatterns("/api/**");
        registry.addInterceptor(scopeFilterInterceptor)
                .addPathPatterns("/api/**");
    }
}
