package com.example.gpcore.testsupport;

import com.example.gpcore.domain.Classification;
import com.example.gpcore.domain.NodePolicy;
import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.port.NodePolicyPort;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory {@link NodePolicyPort}. Unknown nodes return a default {@code PUBLIC}
 * policy so most enforcement tests are driven by the RELATIONSHIP, not policy;
 * specific policies / missing / throwing can be set per node for ABAC and
 * fail-closed tests.
 */
public class FakeNodePolicyPort implements NodePolicyPort {

    private final Map<NodeRef, NodePolicy> policies = new HashMap<>();
    private final Set<NodeRef> missing = new HashSet<>();
    private final Set<NodeRef> throwing = new HashSet<>();
    private NodePolicy defaultPolicy = NodePolicy.of(Classification.PUBLIC);

    public FakeNodePolicyPort put(NodeRef ref, NodePolicy policy) {
        policies.put(ref, policy);
        return this;
    }

    public FakeNodePolicyPort missingFor(NodeRef ref) {
        missing.add(ref);
        return this;
    }

    public FakeNodePolicyPort throwFor(NodeRef ref) {
        throwing.add(ref);
        return this;
    }

    public FakeNodePolicyPort defaultPolicy(NodePolicy policy) {
        this.defaultPolicy = policy;
        return this;
    }

    @Override
    public Optional<NodePolicy> policyOf(NodeRef ref) {
        if (throwing.contains(ref)) {
            throw new RuntimeException("injected policy failure");
        }
        if (missing.contains(ref)) {
            return Optional.empty();
        }
        return Optional.of(policies.getOrDefault(ref, defaultPolicy));
    }
}
