package com.example.meeting.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaConfig;
import com.example.commonauth.openfga.OpenFgaProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires the common-auth {@link OpenFgaAuthzService} from
 * {@code erp.openfga.*} properties. Copied from endpoint-admin-service so
 * the OpenFGA client wiring is identical. Excluded under local/dev where
 * the security chain is permitAll and no authz backend is present.
 */
@Configuration
@Profile("!local & !dev")
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
}
