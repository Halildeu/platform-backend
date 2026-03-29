package com.example.user.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ScopeFilterInterceptor scopeFilterInterceptor;

    public WebMvcConfig(ScopeFilterInterceptor scopeFilterInterceptor) {
        this.scopeFilterInterceptor = scopeFilterInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(scopeFilterInterceptor)
                .addPathPatterns("/api/**");
    }
}
