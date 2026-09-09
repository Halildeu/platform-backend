package com.example.schema.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaConfig;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.commonauth.openfga.OpenFgaStartupGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * OpenFGA wiring for the REPORT module gate (gitops#3608).
 *
 * <p>Mirrors report-service / endpoint-admin: the common-auth factory builds the
 * client from {@code erp.openfga.*}; {@link OpenFgaStartupGuard} is registered
 * explicitly because common-auth's package is not component-scanned here, so a
 * disabled or half-configured OpenFGA is loud at startup instead of silently
 * fail-open.
 */
@Configuration
public class OpenFgaAuthzConfig {

    @Bean
    @ConfigurationProperties(prefix = "erp.openfga")
    public OpenFgaProperties openFgaProperties() {
        return new OpenFgaProperties();
    }

    @Bean
    public OpenFgaAuthzService openFgaAuthzService(OpenFgaProperties props,
                                                   ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return OpenFgaConfig.createAuthzService(props, meterRegistryProvider.getIfAvailable());
    }

    @Bean
    public OpenFgaStartupGuard openFgaStartupGuard(OpenFgaProperties props, Environment environment) {
        return new OpenFgaStartupGuard(props, environment);
    }

    @Bean
    public ReportModuleAccessGate reportModuleAccessGate(OpenFgaAuthzService authzService) {
        return new ReportModuleAccessGate(authzService);
    }
}
