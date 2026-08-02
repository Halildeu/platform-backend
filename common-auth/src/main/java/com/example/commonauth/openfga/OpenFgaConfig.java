package com.example.commonauth.openfga;

import dev.openfga.sdk.api.client.OpenFgaClient;
import io.micrometer.core.instrument.MeterRegistry;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import java.time.Duration;
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
            // Bound the call. Unset, the SDK inherits the JDK's "wait forever", and an
            // unreachable authz plane turns every authorization check into a hang rather
            // than a denial (ES-308, platform-k8s-gitops#2667). A null here would restore
            // that, so an explicitly-cleared property falls back to the documented default
            // instead of silently meaning "no limit".
            var config = new ClientConfiguration()
                    .apiUrl(properties.getApiUrl())
                    .connectTimeout(orDefault(properties.getConnectTimeout(), Duration.ofSeconds(3)))
                    .readTimeout(orDefault(properties.getReadTimeout(), Duration.ofSeconds(10)));

            if (properties.getStoreId() != null && !properties.getStoreId().isBlank()) {
                config.storeId(properties.getStoreId());
            }
            if (properties.getModelId() != null && !properties.getModelId().isBlank()) {
                config.authorizationModelId(properties.getModelId());
            }

            var client = new OpenFgaClient(config);
            log.info("OpenFGA client created: url={}, storeId={}, connectTimeout={}, readTimeout={}",
                    properties.getApiUrl(), properties.getStoreId(),
                    config.getConnectTimeout(), config.getReadTimeout());
            return client;
        } catch (Exception e) {
            log.error("Failed to create OpenFGA client", e);
            return null;
        }
    }

    static Duration orDefault(Duration configured, Duration fallback) {
        // A non-positive value is treated as "unset" rather than "no limit": the SDK reads
        // zero as infinite, and infinite is the exact behaviour this method exists to remove.
        return (configured == null || configured.isZero() || configured.isNegative())
                ? fallback
                : configured;
    }

    /**
     * Create the full authz service (client + dev fallback logic).
     */
    public static OpenFgaAuthzService createAuthzService(OpenFgaProperties properties) {
        OpenFgaClient client = createClient(properties);
        return new OpenFgaAuthzService(client, properties);
    }

    /**
     * B3/B4 (Rev 19): Create authz service with Micrometer metrics support.
     */
    public static OpenFgaAuthzService createAuthzService(OpenFgaProperties properties,
                                                          MeterRegistry meterRegistry) {
        OpenFgaClient client = createClient(properties);
        return new OpenFgaAuthzService(client, properties, meterRegistry);
    }
}
