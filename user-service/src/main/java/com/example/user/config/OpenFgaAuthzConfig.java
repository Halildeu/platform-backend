package com.example.user.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaConfig;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.commonauth.scope.ScopeContextFilter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

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
    public FilterRegistrationBean<ScopeContextFilter> scopeContextFilter(
            OpenFgaAuthzService authzService, OpenFgaProperties props) {
        var reg = new FilterRegistrationBean<>(new ScopeContextFilter(authzService, props));
        reg.addUrlPatterns("/api/*");
        // AFTER Spring Security — so authentication context is available
        reg.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        return reg;
    }
}
