package com.example.report.config;

import com.example.commonauth.AuthenticatedUserLookupService;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaConfig;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.commonauth.scope.RemoteAuthzVersionProvider;
import com.example.commonauth.scope.ScopeContextCache;
import com.example.commonauth.scope.ScopeContextFilter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PR6c-1 (CNS-20260416-003): report-service now talks to OpenFGA via the
 * common-auth {@link OpenFgaAuthzService} directly (C-008 compliance) —
 * removing the previous HTTP dependency on permission-service's
 * {@code /api/v1/authz/me} snapshot endpoint.
 *
 * <p>Pattern mirrors user-service and variant-service. The only divergence
 * is the JdbcTemplate {@code @Qualifier("pgJdbcPlain")} — report-service's
 * primary datasource is MSSQL (read-only reporting), while the authenticated
 * user lookup must hit the PostgreSQL {@code users} database used by
 * user-service.
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
        // MeterRegistry is optional — null-safe when actuator absent (e.g. test context).
        return OpenFgaConfig.createAuthzService(props, meterRegistryProvider.getIfAvailable());
    }

    /**
     * User lookup must run against PostgreSQL (same DB as user-service) — NOT
     * the primary MSSQL datasource. {@code pgJdbcPlain} is a plain
     * {@link JdbcTemplate} bean defined in {@link PostgresDataSourceConfig};
     * {@link AuthenticatedUserLookupService} does not accept the named-parameter
     * variant.
     */
    @Bean
    public AuthenticatedUserLookupService authenticatedUserLookupService(
            @Qualifier("pgJdbcPlain") JdbcTemplate jdbcTemplate,
            @Value("${authz.user-table:user_service.users}") String userTable) {
        return new AuthenticatedUserLookupService(jdbcTemplate, userTable);
    }

    @Bean
    public RemoteAuthzVersionProvider remoteAuthzVersionProvider(
            @Value("${permission.service.base-url:http://permission-service}") String baseUrl) {
        return new RemoteAuthzVersionProvider(baseUrl + "/api/v1/authz/version", 5000);
    }

    @Bean
    public ScopeContextCache scopeContextCache(
            @Value("${scope.cache.enabled:true}") boolean enabled,
            @Value("${scope.cache.ttl-seconds:30}") int ttlSeconds,
            @Value("${scope.cache.ttl-jitter-seconds:3}") int jitterSeconds,
            @Value("${scope.cache.max-size:5000}") long maxSize) {
        return new ScopeContextCache(
                java.time.Duration.ofSeconds(ttlSeconds),
                java.time.Duration.ofSeconds(jitterSeconds),
                maxSize,
                enabled);
    }

    @Bean
    public FilterRegistrationBean<ScopeContextFilter> scopeContextFilter(
            OpenFgaAuthzService authzService,
            OpenFgaProperties props,
            AuthenticatedUserLookupService authenticatedUserLookupService,
            ScopeContextCache scopeContextCache,
            RemoteAuthzVersionProvider remoteAuthzVersionProvider) {
        var reg = new FilterRegistrationBean<>(
                new ScopeContextFilter(authzService, props, authenticatedUserLookupService,
                        scopeContextCache, remoteAuthzVersionProvider)
        );
        reg.addUrlPatterns("/api/*");
        // D-005: MUST be LOWEST_PRECEDENCE - 10 (after Spring Security) so the
        // JWT principal is available when the filter extracts the userId.
        reg.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        return reg;
    }
}
