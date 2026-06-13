package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.ApprovalFatigueLimiter;
import com.example.endpointadmin.remoteaccess.ApprovalFatigueLimiterFactory;
import com.example.endpointadmin.remoteaccess.AuthzGrantResolver;
import com.example.endpointadmin.remoteaccess.AuthzGrantResolverFactory;
import com.example.endpointadmin.remoteaccess.CanonicalIdentityResolver;
import com.example.endpointadmin.remoteaccess.CanonicalIdentityResolverFactory;
import com.example.endpointadmin.remoteaccess.RemoteSessionApprovalFlow;
import com.example.endpointadmin.remoteaccess.RemoteSessionApprovalGate;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ApprovalDecisionAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ApprovalGrantStore;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.JdbcApprovalDecisionAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.LoggingApprovalDecisionAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteSessionApprovalRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

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

    /** The approval-decision audit sink selection. */
    private enum ApprovalAuditSinkType { LOGGING, DURABLE_WORM_DB }

    /**
     * Parse {@code remote-bridge.approval.audit-sink} strictly: blank/unset → the {@code LOGGING} default, but a
     * SET unknown value FAILS FAST (a typo'd {@code DURABLE_WORM_DB} must NOT silently fall back to logging — that
     * would be a config fail-open for an audit-hardening switch).
     */
    private static ApprovalAuditSinkType approvalAuditSinkType(String raw) {
        if (raw == null || raw.isBlank()) {
            return ApprovalAuditSinkType.LOGGING;
        }
        try {
            return ApprovalAuditSinkType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException("unknown remote-bridge.approval.audit-sink: " + raw
                    + " (expected LOGGING or DURABLE_WORM_DB)", unknown);
        }
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

    /**
     * Faz 22.6 D10 post-pilot hardening #2 (Codex 019ec29a) — the approval-decision audit sink, opt-in via
     * {@code remote-bridge.approval.audit-sink}. DEFAULT {@code LOGGING} (the structured app-log baseline).
     * {@code DURABLE_WORM_DB} → the durable WORM {@link JdbcApprovalDecisionAuditSink} (probed fail-fast at
     * refresh). It REQUIRES {@code owner-grant.gate-type=APPROVAL_BACKED_DURABLE_DB}: the durable audit and the
     * grant must share one DB transaction, so a grant can never persist without its durable audit row.
     */
    @Bean
    public ApprovalDecisionAuditSink remoteBridgeApprovalDecisionAuditSink(
            JdbcTemplate jdbcTemplate,
            @Value("${ENDPOINT_ADMIN_DB_SCHEMA:endpoint_admin_service}") String schema,
            @Value("${remote-bridge.approval.audit-sink:LOGGING}") String auditSinkType,
            @Value("${remote-bridge.owner-grant.gate-type:DENY_ALL}") String gateType) {
        if (approvalAuditSinkType(auditSinkType) == ApprovalAuditSinkType.DURABLE_WORM_DB) {
            if (!"APPROVAL_BACKED_DURABLE_DB".equals(gateType == null ? "" : gateType.strip())) {
                throw new IllegalStateException("remote-bridge.approval.audit-sink=DURABLE_WORM_DB requires "
                        + "remote-bridge.owner-grant.gate-type=APPROVAL_BACKED_DURABLE_DB (the grant + audit must "
                        + "share one DB transaction; otherwise a grant could persist without a durable audit row)");
            }
            JdbcApprovalDecisionAuditSink durable = new JdbcApprovalDecisionAuditSink(jdbcTemplate, schema);
            durable.probeAvailable(); // fail-fast: an enabled DURABLE_WORM_DB sink with no table refuses to start
            return durable;
        }
        return new LoggingApprovalDecisionAuditSink();
    }

    /**
     * The transaction boundary the recorder runs the grant + audit writes in. Only the durable path needs a real
     * transaction (so the durable grant + durable audit commit atomically); the LOGGING path runs the same
     * sequence best-effort via {@link TransactionOperations#withoutTransaction()}.
     */
    @Bean
    public TransactionOperations remoteBridgeApprovalTransactionOperations(
            PlatformTransactionManager transactionManager,
            @Value("${remote-bridge.approval.audit-sink:LOGGING}") String auditSinkType) {
        return approvalAuditSinkType(auditSinkType) == ApprovalAuditSinkType.DURABLE_WORM_DB
                ? new TransactionTemplate(transactionManager)
                : TransactionOperations.withoutTransaction();
    }

    @Bean
    public RemoteSessionApprovalRecorder remoteBridgeApprovalRecorder(
            RemoteSessionApprovalFlow remoteBridgeApprovalFlow,
            ApprovalGrantStore remoteBridgeApprovalGrantStore,
            ApprovalDecisionAuditSink remoteBridgeApprovalDecisionAuditSink,
            TransactionOperations remoteBridgeApprovalTransactionOperations,
            RemoteBridgeApprovalProperties props) {
        return new RemoteSessionApprovalRecorder(remoteBridgeApprovalFlow, remoteBridgeApprovalGrantStore,
                props.getGrantTtlMillis(), remoteBridgeApprovalDecisionAuditSink,
                remoteBridgeApprovalTransactionOperations);
    }
}
