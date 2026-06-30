package com.example.gpcore.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaConfig;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.gpcore.authz.ActionRelationPolicy;
import com.example.gpcore.authz.AuthorizationDecisionService;
import com.example.gpcore.authz.DecisionCache;
import com.example.gpcore.authz.DefaultActionRelationPolicy;
import com.example.gpcore.authz.DenyOverridesPolicyEvaluator;
import com.example.gpcore.authz.PolicyDenyEvaluator;
import com.example.gpcore.gateway.ReadGateway;
import com.example.gpcore.gateway.ReadGatewayImpl;
import com.example.gpcore.port.NodePolicyPort;
import com.example.gpcore.port.PolicyVersionProvider;
import com.example.gpcore.port.RelationshipChecker;
import com.example.gpcore.port.SubjectAttributePort;
import com.example.gpcore.port.content.AuditBundlePort;
import com.example.gpcore.port.content.EvidenceReadPort;
import com.example.gpcore.port.content.GraphReadPort;
import com.example.gpcore.port.content.RagChunkPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wires the gp-core enforcement kernel against a SEPARATE OpenFGA store
 * (ADR-0033 isolation): property prefix {@code gp.openfga} (NOT {@code erp.openfga}),
 * its own store/model id, its own v2 model.
 */
@Configuration
public class GpCoreAuthzConfig {

    /** SEPARATE store config — bound from {@code gp.openfga.*}, isolated from Faz 1-25. */
    @Bean
    @ConfigurationProperties(prefix = "gp.openfga")
    public OpenFgaProperties gpOpenFgaProperties() {
        return new OpenFgaProperties();
    }

    @Bean
    public OpenFgaAuthzService gpOpenFgaAuthzService(OpenFgaProperties gpOpenFgaProperties,
                                                     ObjectProvider<MeterRegistry> meterRegistry) {
        return OpenFgaConfig.createAuthzService(gpOpenFgaProperties, meterRegistry.getIfAvailable());
    }

    @Bean
    public RelationshipChecker relationshipChecker(OpenFgaAuthzService gpOpenFgaAuthzService,
                                                   @Value("${gp.authz.dev-bypass:false}") boolean devBypass) {
        return new OpenFgaRelationshipChecker(gpOpenFgaAuthzService, devBypass);
    }

    @Bean
    public ActionRelationPolicy actionRelationPolicy() {
        return new DefaultActionRelationPolicy();
    }

    @Bean
    public PolicyDenyEvaluator policyDenyEvaluator() {
        return new DenyOverridesPolicyEvaluator();
    }

    @Bean
    public DecisionCache decisionCache(@Value("${gp.authz.decision-cache.ttl-seconds:5}") int ttlSeconds,
                                       @Value("${gp.authz.decision-cache.max-size:50000}") long maxSize) {
        return new DecisionCache(Duration.ofSeconds(ttlSeconds), maxSize);
    }

    @Bean
    public AuthorizationDecisionService authorizationDecisionService(
            RelationshipChecker relationshipChecker,
            NodePolicyPort nodePolicyPort,
            SubjectAttributePort subjectAttributePort,
            PolicyVersionProvider policyVersionProvider,
            ActionRelationPolicy actionRelationPolicy,
            PolicyDenyEvaluator policyDenyEvaluator,
            DecisionCache decisionCache,
            OpenFgaProperties gpOpenFgaProperties,
            @Value("${gp.authz.global-edge-allowlist:}") String globalEdgeAllowlist) {
        Set<String> allowlist = Arrays.stream(globalEdgeAllowlist.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return new AuthorizationDecisionService(
                relationshipChecker, nodePolicyPort, subjectAttributePort, policyVersionProvider,
                actionRelationPolicy, policyDenyEvaluator, decisionCache, allowlist,
                nullToEmpty(gpOpenFgaProperties.getStoreId()), nullToEmpty(gpOpenFgaProperties.getModelId()));
    }

    @Bean
    public ReadGateway readGateway(AuthorizationDecisionService authorizationDecisionService,
                                   GraphReadPort graphReadPort,
                                   EvidenceReadPort evidenceReadPort,
                                   RagChunkPort ragChunkPort,
                                   AuditBundlePort auditBundlePort) {
        return new ReadGatewayImpl(authorizationDecisionService, graphReadPort,
                evidenceReadPort, ragChunkPort, auditBundlePort);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
