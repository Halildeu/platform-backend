package com.example.transcript.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Profile("!local & !dev")
public class TranscriptWebMvcConfig implements WebMvcConfigurer {

    private final OpenFgaAuthzService authzService;

    public TranscriptWebMvcConfig(OpenFgaAuthzService authzService) {
        this.authzService = authzService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TranscriptRequireModuleInterceptor(authzService))
                .addPathPatterns("/api/v1/admin/**");
    }
}
