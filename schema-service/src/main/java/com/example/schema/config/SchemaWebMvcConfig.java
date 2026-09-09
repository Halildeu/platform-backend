package com.example.schema.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the REPORT module gate on the schema API surface (gitops#3608). */
@Configuration
public class SchemaWebMvcConfig implements WebMvcConfigurer {

    public static final String GATED_PATH_PATTERN = "/api/v1/schema/**";

    private final ReportModuleAccessGate gate;

    public SchemaWebMvcConfig(ReportModuleAccessGate gate) {
        this.gate = gate;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ReportModuleInterceptor(gate)).addPathPatterns(GATED_PATH_PATTERN);
    }
}
