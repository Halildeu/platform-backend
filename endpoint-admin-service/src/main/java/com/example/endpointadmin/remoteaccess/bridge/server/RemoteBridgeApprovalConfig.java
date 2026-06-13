package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.ApprovalFatigueLimiter;
import com.example.endpointadmin.remoteaccess.ApprovalFatigueLimiterFactory;
import com.example.endpointadmin.remoteaccess.AuthzGrantResolver;
import com.example.endpointadmin.remoteaccess.AuthzGrantResolverFactory;
import com.example.endpointadmin.remoteaccess.CanonicalIdentityResolver;
import com.example.endpointadmin.remoteaccess.CanonicalIdentityResolverFactory;
import com.example.endpointadmin.remoteaccess.RemoteSessionApprovalFlow;
import com.example.endpointadmin.remoteaccess.RemoteSessionApprovalGate;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ApprovalGrantStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteSessionApprovalRecorder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Locale;

/**
 * Faz 22.6 D10 approval-chain wiring (Codex 019ebe06; PR-1 of the approval write-path slice) — wires the E10
 * dual-control chain (canonical identity → grants → gate → flow → recorder) so the (PR-2) approval REST endpoint
 * can record an approval into the {@link ApprovalGrantStore}.
 *
 * <p><b>Separate authority surface</b> (Codex): gated by its OWN {@code remote-bridge.approval-rest.enabled}
 * (default false) — enabling the operator transport does NOT auto-expose the approval write-path. Every in-memory
 * placeholder (canonical resolver, tenant-scoped grants, fatigue limiter) is **fail-fast prod-forbidden**: a
 * production runtime must use the live IdP / OpenFGA / durable limiter. The {@link ApprovalGrantStore} bean comes
 * from {@code RemoteBridgeServerConfig} (so {@code remote-bridge.enabled} must also be true).
 */
@Configuration
@ConditionalOnProperty(prefix = "remote-bridge.approval-rest", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RemoteBridgeApprovalProperties.class)
public class RemoteBridgeApprovalConfig {

    private static boolean productionLike(Environment environment) {
        String profiles = environment.getActiveProfiles().length == 0 ? "" : String.join(",",
                environment.getActiveProfiles()).toLowerCase(Locale.ROOT);
        return profiles.contains("prod");
    }

    @Bean
    public CanonicalIdentityResolver remoteBridgeCanonicalIdentityResolver(Environment environment,
                                                                           RemoteBridgeApprovalProperties props) {
        // explicit principal→canonical mapping (no pass-through); prod-forbidden in-memory placeholder
        if (props.getCanonicalIdentity().isEmpty()) {
            throw new IllegalStateException("remote-bridge.approval-rest is enabled but no "
                    + "remote-bridge.approval.canonical-identity mapping is configured — every approval would "
                    + "fail-closed (unresolved identity); configure the pilot operators (fail-fast)");
        }
        return CanonicalIdentityResolverFactory.create(CanonicalIdentityResolverFactory.ResolverType.IN_MEMORY,
                props.getCanonicalIdentity(), productionLike(environment));
    }

    @Bean
    public AuthzGrantResolver remoteBridgeAuthzGrantResolver(Environment environment,
                                                             RemoteBridgeApprovalProperties props) {
        // TENANT-SCOPED grants: a principal granted in a tenant holds it for ANY (dynamic) session in that tenant
        if (props.getGrants().getCanApprove().isEmpty() || props.getGrants().getCanRequest().isEmpty()) {
            throw new IllegalStateException("remote-bridge.approval-rest is enabled but "
                    + "remote-bridge.approval.grants.can-request / can-approve is empty — no approval could ever "
                    + "be granted; configure the pilot grants (fail-fast)");
        }
        return AuthzGrantResolverFactory.createTenantScoped(
                RemoteBridgeApprovalProperties.copyTenantGrants(props.getGrants().getCanRequest()),
                RemoteBridgeApprovalProperties.copyTenantGrants(props.getGrants().getCanApprove()),
                productionLike(environment));
    }

    @Bean
    public ApprovalFatigueLimiter remoteBridgeApprovalFatigueLimiter(Environment environment,
                                                                     RemoteBridgeApprovalProperties props) {
        // prod-forbidden in-memory (a process-local cap is bypassable across restart/replicas)
        return ApprovalFatigueLimiterFactory.create(ApprovalFatigueLimiterFactory.LimiterType.IN_MEMORY,
                props.getFatigue().getMaxPerWindow(), props.getFatigue().getWindowMillis(),
                productionLike(environment));
    }

    @Bean
    public RemoteSessionApprovalGate remoteBridgeApprovalGate(
            CanonicalIdentityResolver remoteBridgeCanonicalIdentityResolver,
            ApprovalFatigueLimiter remoteBridgeApprovalFatigueLimiter) {
        return new RemoteSessionApprovalGate(remoteBridgeCanonicalIdentityResolver,
                remoteBridgeApprovalFatigueLimiter);
    }

    @Bean
    public RemoteSessionApprovalFlow remoteBridgeApprovalFlow(
            AuthzGrantResolver remoteBridgeAuthzGrantResolver,
            RemoteSessionApprovalGate remoteBridgeApprovalGate) {
        return new RemoteSessionApprovalFlow(remoteBridgeAuthzGrantResolver, remoteBridgeApprovalGate);
    }

    @Bean
    public RemoteSessionApprovalRecorder remoteBridgeApprovalRecorder(
            RemoteSessionApprovalFlow remoteBridgeApprovalFlow,
            ApprovalGrantStore remoteBridgeApprovalGrantStore,
            RemoteBridgeApprovalProperties props) {
        return new RemoteSessionApprovalRecorder(remoteBridgeApprovalFlow, remoteBridgeApprovalGrantStore,
                props.getGrantTtlMillis());
    }
}
