package com.example.coredata.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnBean(ScopeFilterInterceptor.class)
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
