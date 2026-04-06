package com.example.variant.config;

import com.example.commonauth.AuthenticatedUserLookupService;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaConfig;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.commonauth.scope.ScopeContextFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class OpenFgaAuthzConfig {

    @Bean
    @ConfigurationProperties(prefix = "erp.openfga")
    public OpenFgaProperties openFgaProperties() {
        return new OpenFgaProperties();
    }

    @Bean
    public OpenFgaAuthzService openFgaAuthzService(OpenFgaProperties props) {
        return OpenFgaConfig.createAuthzService(props);
    }

    @Bean
    public AuthenticatedUserLookupService authenticatedUserLookupService(
            JdbcTemplate jdbcTemplate,
            @Value("${authz.user-table:user_service.users}") String userTable) {
        return new AuthenticatedUserLookupService(jdbcTemplate, userTable);
    }

    @Bean
    public FilterRegistrationBean<ScopeContextFilter> scopeContextFilter(
            OpenFgaAuthzService authzService,
            OpenFgaProperties props,
            AuthenticatedUserLookupService authenticatedUserLookupService) {
        var reg = new FilterRegistrationBean<>(
                new ScopeContextFilter(authzService, props, authenticatedUserLookupService)
        );
        reg.addUrlPatterns("/api/*");
        // AFTER Spring Security — so authentication context is available
        reg.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        return reg;
    }
}
