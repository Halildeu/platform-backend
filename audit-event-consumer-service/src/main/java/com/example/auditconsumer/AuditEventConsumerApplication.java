package com.example.auditconsumer;

import com.example.auditconsumer.config.AuditConsumerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — audit-event-consumer-service.
 *
 * <p>Reads the audio-gateway {@code audit:events} Redis stream with a consumer
 * group and persists each event to an immutable, tenant-scoped hash-chained
 * {@code audit_event} table.
 *
 * <p><b>Security auto-config is excluded.</b> {@code spring-security-web} +
 * {@code -core} arrive transitively via {@code common-auth} (oauth2
 * resource-server), but this service has NO public business HTTP surface — it is
 * a Redis Streams consumer plus an internal persistence/verifier path, and the
 * only HTTP endpoints are the cluster-internal actuator probes. We exclude
 * {@link SecurityAutoConfiguration} (and the generated-password
 * {@link UserDetailsServiceAutoConfiguration}) so the actuator stays open on the
 * management port without dragging in the full {@code spring-boot-starter-security}
 * (and its {@code spring-security-config} DSL) just to permit it. If a protected
 * admin/verify endpoint is ever added, switch to a JWT-authenticated
 * {@code SecurityFilterChain} (endpoint-admin {@code SecurityConfig} pattern).
 */
@SpringBootApplication(exclude = {
        SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class
})
@EnableConfigurationProperties(AuditConsumerProperties.class)
@EnableScheduling
public class AuditEventConsumerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditEventConsumerApplication.class, args);
    }
}
