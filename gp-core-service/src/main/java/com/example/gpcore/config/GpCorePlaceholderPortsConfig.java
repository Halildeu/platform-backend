package com.example.gpcore.config;

import com.example.gpcore.domain.SubjectAttributes;
import com.example.gpcore.port.NodePolicyPort;
import com.example.gpcore.port.PolicyVersionProvider;
import com.example.gpcore.port.SubjectAttributePort;
import com.example.gpcore.port.content.AuditBundlePort;
import com.example.gpcore.port.content.EvidenceReadPort;
import com.example.gpcore.port.content.GraphReadPort;
import com.example.gpcore.port.content.RagChunkPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Wave-1 placeholder ports so the service boots as a real skeleton. They are
 * fail-closed/empty — no fabricated data: content ports return nothing, the
 * policy port returns no policy (→ deny "policy_missing"), and the version
 * provider returns fixed stamps. Wave 2 (typed PG node/edge schema, OpenFGA
 * tuple writer, evidence ledger, RAG) supplies the real implementations, which
 * override these via {@link ConditionalOnMissingBean}.
 *
 * <p>Net effect with no real data + OpenFGA disabled: every gateway read denies
 * / returns empty. That is the correct enforcement-kernel default — there is no
 * "allow because unconfigured" path.
 */
@Configuration
public class GpCorePlaceholderPortsConfig {

    private static final Logger log = LoggerFactory.getLogger(GpCorePlaceholderPortsConfig.class);

    @Bean
    @ConditionalOnMissingBean
    public GraphReadPort graphReadPort() {
        return new GraphReadPort() {
            @Override public Optional<com.example.gpcore.gateway.model.NodeView> findNode(com.example.gpcore.domain.NodeRef ref) { return Optional.empty(); }
            @Override public List<com.example.gpcore.domain.Edge> findEdges(com.example.gpcore.domain.NodeRef entry, com.example.gpcore.gateway.model.EdgeQuery query) { return List.of(); }
            @Override public List<com.example.gpcore.domain.NodeRef> searchCandidates(com.example.gpcore.gateway.model.SearchQuery query) { return List.of(); }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public EvidenceReadPort evidenceReadPort() {
        return new EvidenceReadPort() {
            @Override public Optional<com.example.gpcore.gateway.model.EvidenceMetadataView> findMetadata(com.example.gpcore.domain.NodeRef ref) { return Optional.empty(); }
            @Override public Optional<com.example.gpcore.gateway.model.EvidenceContent> resolveContent(com.example.gpcore.domain.NodeRef ref) { return Optional.empty(); }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public RagChunkPort ragChunkPort() {
        return new RagChunkPort() {
            @Override public Optional<com.example.gpcore.domain.NodeRef> authoritativeRef(String chunkId) { return Optional.empty(); }
            @Override public Optional<String> resolveChunkText(String chunkId) { return Optional.empty(); }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public AuditBundlePort auditBundlePort() {
        return scope -> List.of();
    }

    @Bean
    @ConditionalOnMissingBean
    public NodePolicyPort nodePolicyPort() {
        // No policy known yet → decision service treats as deny (fail-closed).
        return ref -> Optional.empty();
    }

    @Bean
    @ConditionalOnMissingBean
    public SubjectAttributePort subjectAttributePort() {
        // Known subject, no elevated clearances; subject-policy version fixed for Wave 1.
        return principal -> Optional.of(new SubjectAttributes(Set.of(), "subject-v0"));
    }

    @Bean
    @ConditionalOnMissingBean
    public PolicyVersionProvider policyVersionProvider() {
        return new PolicyVersionProvider() {
            @Override public String policyVersion() { return "policy-v0"; }
            @Override public String tupleRevision() { return "tuple-r0"; }
        };
    }

    @Bean
    public ApplicationRunner gpCorePlaceholderPortsBanner() {
        return args -> log.warn("gp-core Wave 1: placeholder data ports active (fail-closed/empty). "
                + "Wave 2 (PG schema, tuple writer, evidence ledger, RAG) will supply real ports.");
    }
}
