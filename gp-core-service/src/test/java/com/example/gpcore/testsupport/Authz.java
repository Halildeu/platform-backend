package com.example.gpcore.testsupport;

import com.example.gpcore.authz.AuthorizationDecisionService;
import com.example.gpcore.authz.DecisionCache;
import com.example.gpcore.authz.DefaultActionRelationPolicy;
import com.example.gpcore.authz.DenyOverridesPolicyEvaluator;
import com.example.gpcore.port.NodePolicyPort;
import com.example.gpcore.port.PolicyVersionProvider;
import com.example.gpcore.port.RelationshipChecker;
import com.example.gpcore.port.SubjectAttributePort;

import java.time.Duration;
import java.util.Set;

/** Test factory for an {@link AuthorizationDecisionService} wired with the production policy classes. */
public final class Authz {

    public static final String STORE = "gp-store-test";
    public static final String MODEL = "gp-model-test";

    private Authz() {
    }

    public static AuthorizationDecisionService service(RelationshipChecker checker,
                                                       NodePolicyPort policy,
                                                       SubjectAttributePort subject,
                                                       PolicyVersionProvider versions,
                                                       DecisionCache cache,
                                                       Set<String> globalEdgeAllowlist) {
        return new AuthorizationDecisionService(checker, policy, subject, versions,
                new DefaultActionRelationPolicy(), new DenyOverridesPolicyEvaluator(),
                cache, globalEdgeAllowlist, STORE, MODEL);
    }

    /** Convenience: enabled 5s cache, empty global-edge allowlist. */
    public static AuthorizationDecisionService service(RelationshipChecker checker,
                                                       NodePolicyPort policy,
                                                       SubjectAttributePort subject,
                                                       PolicyVersionProvider versions) {
        return service(checker, policy, subject, versions,
                new DecisionCache(Duration.ofSeconds(5), 10_000), Set.of());
    }

    public static DecisionCache enabledCache() {
        return new DecisionCache(Duration.ofSeconds(5), 10_000);
    }
}
