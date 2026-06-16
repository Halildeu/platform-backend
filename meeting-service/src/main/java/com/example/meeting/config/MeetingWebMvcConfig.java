package com.example.meeting.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link MeetingRequireModuleInterceptor} on the admin route
 * tree. Copied from endpoint-admin-service {@code EndpointAdminWebMvcConfig}.
 */
@Configuration
@Profile("!local & !dev")
public class MeetingWebMvcConfig implements WebMvcConfigurer {

    private final OpenFgaAuthzService authzService;

    public MeetingWebMvcConfig(OpenFgaAuthzService authzService) {
        this.authzService = authzService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new MeetingRequireModuleInterceptor(authzService))
                .addPathPatterns("/api/v1/admin/**");
    }
}
