package com.example.schema.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.time.Duration;

/**
 * Wiring for the REPORT module gate (gitops#3608).
 *
 * <p>Fail-closed by construction: the gate is on by default in every profile; a
 * {@code k8s} deployment that turns it off refuses to start (a pod that does not
 * come up keeps the previous, gated pod serving — a silently open catalogue
 * would not). Only the dev profiles common-auth already treats as such
 * ({@code local}, {@code dev}, {@code test}, {@code conntest}) may run with the
 * gate off, and there it logs that it is doing so.
 */
@Configuration
@EnableConfigurationProperties(ReportModuleGateConfig.Properties.class)
public class ReportModuleGateConfig {

    private static final Logger log = LoggerFactory.getLogger(ReportModuleGateConfig.class);
    static final Profiles DEV_PROFILES = Profiles.of("local", "dev", "test", "conntest");
    static final Profiles CLUSTER_PROFILE = Profiles.of("k8s");

    @ConfigurationProperties(prefix = "schema.report-gate")
    public record Properties(boolean enabled,
                             String permissionServiceBaseUrl,
                             Duration cacheTtl,
                             Duration revisionMemo,
                             Duration connectTimeout,
                             Duration requestTimeout) {
        public Properties {
            if (permissionServiceBaseUrl == null || permissionServiceBaseUrl.isBlank()) {
                permissionServiceBaseUrl = "http://permission-service:8090";
            }
            if (cacheTtl == null) {
                cacheTtl = Duration.ofSeconds(10);
            }
            if (revisionMemo == null) {
                revisionMemo = Duration.ofSeconds(5);
            }
            if (connectTimeout == null) {
                connectTimeout = Duration.ofSeconds(2);
            }
            if (requestTimeout == null) {
                requestTimeout = Duration.ofSeconds(3);
            }
        }
    }

    @Bean
    public AuthzMeClient authzMeClient(Properties props) {
        return new HttpAuthzMeClient(props.permissionServiceBaseUrl(), props.connectTimeout(), props.requestTimeout());
    }

    @Bean
    public ReportModuleAccessGate reportModuleAccessGate(Properties props, AuthzMeClient client,
                                                         Environment environment) {
        boolean dev = environment.acceptsProfiles(DEV_PROFILES);
        if (!props.enabled()) {
            if (environment.acceptsProfiles(CLUSTER_PROFILE)) {
                throw new IllegalStateException(
                        "schema.report-gate.enabled=false is not allowed in the k8s profile: "
                                + "/api/v1/schema/** would be readable by any authenticated user (gitops#3608)");
            }
            log.warn("REPORT module gate is DISABLED (profiles {}) — {}",
                    String.join(",", environment.getActiveProfiles()),
                    dev ? "dev pass-through" : "non-dev profile: every gated call will be denied");
        }
        return new ReportModuleAccessGate(client, props.enabled(), dev, props.cacheTtl(), props.revisionMemo());
    }
}
