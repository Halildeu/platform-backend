package com.example.gpcore.authz;

import com.example.gpcore.domain.Action;
import com.example.gpcore.domain.NodePolicy;
import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.domain.Principal;
import com.example.gpcore.domain.SubjectAttributes;

import java.util.Locale;
import java.util.Optional;

/**
 * Default deny-overrides rules (ADR-0034 KVKK + ADR-0035 §3). Each rule yields a
 * deny that overrides any OpenFGA allow. Order does not matter — the first match
 * is returned.
 *
 * <ol>
 *   <li><b>Legal hold</b>: blocks {@code EXPORT}/{@code DOWNLOAD} (and disposition);
 *       in-app {@code VIEW} and {@code RAG_READ} of held content remain allowed
 *       (preservation, not concealment).</li>
 *   <li><b>Clearance</b>: at/above {@code RESTRICTED} (incl. KVKK
 *       {@code SPECIAL_CATEGORY}), the sensitive actions {@code RAG_READ} /
 *       {@code EXPORT} / {@code DOWNLOAD} require the subject to hold the matching
 *       clearance token; else deny. ({@code VIEW} by an authorized viewer stays
 *       allowed — AI/export are the leak vectors.)</li>
 *   <li><b>Deny tags</b>: a policy tag {@code deny:<action>} (or {@code deny:all})
 *       denies that action explicitly.</li>
 * </ol>
 */
public class DenyOverridesPolicyEvaluator implements PolicyDenyEvaluator {

    public static final String DENY_ALL_TAG = "deny:all";

    @Override
    public Optional<String> evaluateDeny(Principal principal, SubjectAttributes subject,
                                         NodeRef ref, NodePolicy policy, Action action) {
        // 1. Legal hold blocks export/download (preserve, don't exfiltrate).
        if (policy.legalHold() && (action == Action.EXPORT || action == Action.DOWNLOAD)) {
            return Optional.of("abac:legal_hold");
        }

        // 2. Clearance for restricted/special-category on sensitive actions.
        if (policy.classification().requiresClearance() && isSensitive(action)) {
            String required = policy.classification().clearanceToken();
            if (subject == null || !subject.hasClearance(required)) {
                return Optional.of("abac:clearance_required:"
                        + policy.classification().name().toLowerCase(Locale.ROOT));
            }
        }

        // 3. Explicit deny tags.
        if (policy.hasTag(DENY_ALL_TAG)) {
            return Optional.of("abac:policy_tag:deny_all");
        }
        String actionTag = "deny:" + action.name().toLowerCase(Locale.ROOT);
        if (policy.hasTag(actionTag)) {
            return Optional.of("abac:policy_tag:" + actionTag);
        }

        return Optional.empty();
    }

    private static boolean isSensitive(Action action) {
        return action == Action.RAG_READ || action == Action.EXPORT || action == Action.DOWNLOAD;
    }
}
