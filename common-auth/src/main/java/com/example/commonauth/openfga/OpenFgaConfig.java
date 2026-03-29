package com.example.commonauth.openfga;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating OpenFGA client and service instances.
 * Services should call these methods in their @Configuration class.
 *
 * Example usage in a service's config:
 * <pre>
 * {@code
 * @Configuration
 * public class AuthzConfig {
 *     @Bean
 *     @ConfigurationProperties(prefix = "erp.openfga")
 *     public OpenFgaProperties openFgaProperties() {
 *         return new OpenFgaProperties();
 *     }
 *
 *     @Bean
 *     public OpenFgaAuthzService openFgaAuthzService(OpenFgaProperties props) {
 *         return OpenFgaConfig.createAuthzService(props);
 *     }
 * }
 * }
 * </pre>
 */
public final class OpenFgaConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenFgaConfig.class);

    private OpenFgaConfig() {
    }

    /**
     * Create an OpenFgaClient from properties. Returns null if disabled.
     */
    public static OpenFgaClient createClient(OpenFgaProperties properties) {
        if (!properties.isEnabled()) {
            log.info("OpenFGA disabled — no client created");
            return null;
        }

        try {
            var config = new ClientConfiguration()
                    .apiUrl(properties.getApiUrl());

            if (properties.getStoreId() != null && !properties.getStoreId().isBlank()) {
                config.storeId(properties.getStoreId());
            }
            if (properties.getModelId() != null && !properties.getModelId().isBlank()) {
                config.authorizationModelId(properties.getModelId());
            }

            var client = new OpenFgaClient(config);
            log.info("OpenFGA client created: url={}, storeId={}",
                    properties.getApiUrl(), properties.getStoreId());
            return client;
        } catch (Exception e) {
            log.error("Failed to create OpenFGA client", e);
            return null;
        }
    }

    /**
     * Create the full authz service (client + dev fallback logic).
     */
    public static OpenFgaAuthzService createAuthzService(OpenFgaProperties properties) {
        OpenFgaClient client = createClient(properties);
        return new OpenFgaAuthzService(client, properties);
    }
}
