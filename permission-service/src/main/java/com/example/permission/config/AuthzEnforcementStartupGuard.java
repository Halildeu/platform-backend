package com.example.permission.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fail closed where it matters (#933).
 *
 * <p>Making {@code OpenFgaAuthzService} unconditional (see {@link OpenFgaAuthzBaseConfig})
 * fixes the context-load crash, but it trades a loud failure for a quiet one: with
 * {@code erp.openfga.enabled=false} the service now starts and every {@code @RequireModule}
 * guard permits the request, because {@link RequireModuleInterceptor} short-circuits on
 * {@code !authzService.isEnabled()}. That permissive mode is intentional and documented for
 * local development — it is NOT acceptable in a deployed environment, and previously the
 * only thing preventing it was an accident (a missing bean).
 *
 * <p>So the permissive mode is kept, but made impossible to reach silently:
 *
 * <ul>
 *   <li>a deployed profile is active and enforcement is off → refuse to start, naming the
 *       exact property, so the failure says <em>why</em> instead of
 *       "No qualifying bean of type OpenFgaAuthzService";</li>
 *   <li>otherwise → a single unmissable WARN block at startup.</li>
 * </ul>
 *
 * <p>{@code authz.enforcement.required-profiles} is configurable so a new deployed tier can
 * be added without touching code; it defaults to {@code k8s}, the profile used by
 * {@code application-k8s.yml} (which already sets the flag to {@code true}, so this guard is
 * a no-op today and exists to catch a future misconfiguration).
 *
 * <p>Set {@code authz.enforcement.allow-disabled-in-deployed-profile=true} to override — an
 * explicit, greppable, auditable opt-out rather than a silent default.
 */
@Component
public class AuthzEnforcementStartupGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AuthzEnforcementStartupGuard.class);

    private final OpenFgaAuthzService authzService;
    private final Environment environment;
    private final Set<String> requiredProfiles;
    private final boolean allowDisabledInDeployedProfile;

    public AuthzEnforcementStartupGuard(
            OpenFgaAuthzService authzService,
            Environment environment,
            @Value("${authz.enforcement.required-profiles:k8s}") String requiredProfilesCsv,
            @Value("${authz.enforcement.allow-disabled-in-deployed-profile:false}") boolean allowDisabledInDeployedProfile) {
        this.authzService = authzService;
        this.environment = environment;
        this.allowDisabledInDeployedProfile = allowDisabledInDeployedProfile;
        this.requiredProfiles = Arrays.stream(requiredProfilesCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public void afterPropertiesSet() {
        if (authzService.isEnabled()) {
            return;
        }

        List<String> active = Arrays.asList(environment.getActiveProfiles());
        List<String> offending = active.stream().filter(requiredProfiles::contains).toList();

        if (!offending.isEmpty() && !allowDisabledInDeployedProfile) {
            throw new IllegalStateException(
                    "Authorization enforcement is DISABLED (erp.openfga.enabled=false) while a deployed "
                            + "profile is active " + offending + ". @RequireModule guards would permit every "
                            + "request. Set erp.openfga.enabled=true (ERP_OPENFGA_ENABLED=true), or — only if "
                            + "this is deliberate — set authz.enforcement.allow-disabled-in-deployed-profile=true.");
        }

        log.warn("================================================================");
        log.warn("AUTHORIZATION ENFORCEMENT IS DISABLED (erp.openfga.enabled=false)");
        log.warn("@RequireModule guards permit ALL requests (dev/permitAll mode).");
        log.warn("Active profiles: {}", active.isEmpty() ? "[default]" : active);
        log.warn("This must never be used in a deployed environment.");
        log.warn("================================================================");
    }
}
