package com.example.gpcore.authz;

import com.example.gpcore.domain.Action;
import com.example.gpcore.domain.NodePolicy;
import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.domain.Principal;
import com.example.gpcore.domain.SubjectAttributes;

import java.util.Optional;

/**
 * The deny-overrides ABAC layer (ADR-0035 §3). OpenFGA answers "who is related";
 * this layer answers "may this data leave in THIS context". A deny here ALWAYS
 * overrides an OpenFGA allow — it can never grant, only deny.
 */
public interface PolicyDenyEvaluator {

    /**
     * @return a deny reason if this context forbids {@code action}, else empty
     *         (no ABAC objection — the OpenFGA allow stands).
     */
    Optional<String> evaluateDeny(Principal principal, SubjectAttributes subject,
                                  NodeRef ref, NodePolicy policy, Action action);
}
