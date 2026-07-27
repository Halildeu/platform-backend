package com.example.permission.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaConfig;
import com.example.commonauth.openfga.OpenFgaProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenFGA properties + authz service — deliberately UNCONDITIONAL (#933).
 *
 * <p>These two beans used to live in {@link OpenFgaAuthzConfig}, which carries
 * {@code @ConditionalOnProperty(erp.openfga.enabled=true, matchIfMissing=false)}.
 * That produced a contradiction: three separate runtime paths were written to
 * handle the disabled state —
 *
 * <ul>
 *   <li>{@code OpenFgaConfig.createClient} returns {@code null} and logs
 *       "OpenFGA disabled — no client created";</li>
 *   <li>{@code OpenFgaAuthzService} computes {@code enabled = props.isEnabled() && client != null}
 *       and documents "when disabled (dev/permitAll mode), all checks return true";</li>
 *   <li>{@link RequireModuleInterceptor} short-circuits with
 *       {@code if (!authzService.isEnabled()) return true;}</li>
 * </ul>
 *
 * <p>…but the bean factory refused to create the object that carries that state, so
 * {@link WebMvcConfig} — which hard-requires {@code OpenFgaAuthzService} — killed the
 * ApplicationContext instead:
 *
 * <pre>
 * UnsatisfiedDependencyException: bean 'webMvcConfig' ctor param 1:
 *   No qualifying bean of type 'com.example.commonauth.openfga.OpenFgaAuthzService'
 * </pre>
 *
 * <p>That mattered because {@code erp.openfga.enabled} defaults to {@code false} in
 * permission-service's own {@code application.properties} — the service could not start
 * in its own default configuration. Only {@code application-k8s.yml} defaults it to
 * {@code true}.
 *
 * <p>Keeping these two beans unconditional makes {@code authzService.isEnabled()} the
 * single source of truth for "is enforcement on", instead of "does the bean exist".
 * The remaining beans in {@link OpenFgaAuthzConfig} stay conditional: one of them needs
 * {@code AuthzVersionService}, which is itself gated on the same flag.
 *
 * <p>Because a running-but-unenforced service is a worse failure mode than a crash,
 * {@link AuthzEnforcementStartupGuard} refuses to start when enforcement is off in a
 * deployed profile, and warns loudly otherwise.
 */
@Configuration
public class OpenFgaAuthzBaseConfig {

    @Bean
    @ConfigurationProperties(prefix = "erp.openfga")
    public OpenFgaProperties openFgaProperties() {
        return new OpenFgaProperties();
    }

    @Bean
    public OpenFgaAuthzService openFgaAuthzService(OpenFgaProperties props,
                                                  ObjectProvider<MeterRegistry> meterRegistryProvider) {
        // B3/B4 (Rev 19): MeterRegistry optional — null safe when actuator absent (test context).
        // When props.isEnabled() is false the factory returns a service with a null client,
        // i.e. the documented dev/permitAll instance — not an error.
        return OpenFgaConfig.createAuthzService(props, meterRegistryProvider.getIfAvailable());
    }
}
