package com.example.endpointadmin.remoteaccess;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Faz 22.6 remote-access wiring. For this skeleton it only binds {@link RemoteAccessProperties}
 * (disabled-by-default flag) — there is no runtime broker bean yet. The future runtime broker
 * service will be {@code @ConditionalOnProperty("endpoint-admin.remote-access.enabled")} so it is
 * absent from the context unless explicitly enabled (ADR-0034 #1388/D10).
 */
@Configuration
@EnableConfigurationProperties(RemoteAccessProperties.class)
public class RemoteAccessConfig {
}
