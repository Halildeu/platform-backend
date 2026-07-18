package com.example.ethics.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaConfig;
import com.example.commonauth.openfga.OpenFgaProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EthicsOpenFgaConfig {
    @Bean
    @ConfigurationProperties(prefix = "erp.openfga")
    OpenFgaProperties ethicsOpenFgaProperties() {
        return new OpenFgaProperties();
    }

    @Bean
    OpenFgaAuthzService ethicsOpenFgaAuthzService(
            OpenFgaProperties properties,
            ObjectProvider<MeterRegistry> meterRegistry) {
        return OpenFgaConfig.createAuthzService(properties, meterRegistry.getIfAvailable());
    }
}
