package com.example.ethics.intake;

import com.example.ethics.api.EthicsDtos.ReportMode;
import com.example.ethics.model.OrgReportPolicy;
import com.example.ethics.repository.OrgReportPolicyRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ES-212 (#3370) — may this organisation accept a report in this mode?
 *
 * <p>Owner decision, 2026-08-02: each company sets its own KVKK posture. One runs named
 * reports so managers can follow up directly; the next has a works council that permits
 * anonymous only. So the answer is per-tenant data, not a constant.
 *
 * <h2>Why the failure mode here is neither of the usual two</h2>
 *
 * <p>{@link IntakeChannelGate} fails <em>open</em>, and its reasoning is sound: a form that
 * closes on a bad read shuts the channel someone is using to report wrongdoing.
 * {@code EthicsEntitlements} fails <em>closed</em>, equally soundly: a capability that opens
 * on a bad read hands out something nobody bought.
 *
 * <p>Applying either rule wholesale here gets it wrong. Fail open, and an unreadable policy
 * table starts collecting names from people at a company that never turned identity
 * collection on — personal data gathered with no lawful basis, and unlike a wrong access
 * decision it cannot be taken back once the person has typed their name. Fail closed in the
 * ordinary sense, and the reporting channel shuts over a database hiccup.
 *
 * <p>So this gate degrades to the <strong>anonymous floor</strong>: on any doubt the channel
 * stays open and identity collection stops. Reporting never becomes impossible; it becomes
 * anonymous. That is the safe direction on both axes at once, and it is why
 * {@code anonymous_enabled} is pinned true in the schema rather than left switchable — the
 * floor has to be something no configuration, and no failure, can remove.
 */
@Component
public class ReportModePolicy {

    private static final Logger log = LoggerFactory.getLogger(ReportModePolicy.class);

    private final OrgReportPolicyRepository policies;

    public ReportModePolicy(OrgReportPolicyRepository policies) {
        this.policies = policies;
    }

    /**
     * @return true when the org has explicitly enabled this mode. Anonymous is always
     *     true. An org with no policy row has not opted in to anything, so it behaves
     *     exactly as it did before this feature existed — which is what makes shipping
     *     this migration safe for every tenant already running.
     */
    public boolean isEnabled(UUID orgId, ReportMode mode) {
        if (mode == ReportMode.ANONYMOUS) {
            return true;
        }
        Optional<OrgReportPolicy> policy;
        try {
            policy = policies.findById(orgId);
        } catch (RuntimeException e) {
            // Degrade to the floor rather than guessing. Logged without the org id's
            // surrounding context because this line can fire during an outage and the
            // useful signal is "identity collection is off", not who was affected.
            log.warn("report policy unreadable; falling back to anonymous-only intake", e);
            return false;
        }
        return policy.map(p -> switch (mode) {
            case CONFIDENTIAL -> p.isConfidentialEnabled();
            case NAMED -> p.isNamedEnabled();
            case ANONYMOUS -> true;
        }).orElse(false);
    }

    /**
     * Whether a mode carries an identity at all. Used by intake to decide if an identity
     * payload is required or must be refused — the two questions are separate, because a
     * mode being <em>enabled</em> says nothing about whether it <em>collects</em>.
     */
    public static boolean collectsIdentity(ReportMode mode) {
        return mode != ReportMode.ANONYMOUS;
    }
}
