package com.example.ethics.intake;

import com.example.ethics.model.OrgSubscription;
import com.example.ethics.repository.OrgSubscriptionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ES-403 (#885) — is the intake channel on at all for this organisation?
 *
 * <p>Owner decision, 2026-08-01: when a subscription lapses, <strong>only new report
 * intake</strong> closes. Open cases keep everything — evidence, reporter messages, case
 * handling, the SLA clock, export — because shutting any of those transfers the
 * organisation's EU 2019/1937 Art.9 obligations onto a billing event, and refusing evidence
 * on an open case is indefensible.
 *
 * <p>This class deliberately does NOT live in the {@code catalog} package and does not
 * answer capability questions. {@link com.example.ethics.catalog.EthicsEntitlements} answers
 * "what did the organisation buy" for <em>staff-side</em> features and fails
 * <em>closed</em>; this gate answers "did the subscription lapse" for the one public
 * surface allowed to react to it, and fails <em>open</em>. The inversion is the point:
 *
 * <ul>
 *   <li>A staff feature that opens on a bad read hands out a capability nobody bought —
 *       fail closed.
 *   <li>An intake form that closes on a bad read shuts the channel someone is using to
 *       report wrongdoing — the failure this product exists to prevent. So an unreadable
 *       store answers <strong>open</strong>, and only an <em>established</em> lapse closes
 *       the form.
 * </ul>
 *
 * <p>"Established lapse" is deliberately narrow, in the org's favour on every edge:
 *
 * <ul>
 *   <li>any active subscription → <strong>open</strong>
 *   <li>no subscription rows at all → <strong>open</strong> — a never-subscribed org has
 *       not <em>lapsed</em>; hosts are only mapped for actual tenants, and refusing a
 *       provisioning-window tenant would close a channel over paperwork ordering
 *   <li>all rows revoked, newest {@code revoked_at} within the grace window →
 *       <strong>open</strong> (default 14 days; tenant-parametric per the retention policy
 *       §1a — a tenant may lengthen it, never shorten below the default)
 *   <li>all rows revoked, newest {@code revoked_at} beyond grace → <strong>closed</strong>
 *   <li>store unreadable and no unexpired cached answer → <strong>open</strong>
 * </ul>
 *
 * <p>The public UI pairs the refusal with the external reporting channels — Directive
 * 2019/1937 Art.9(1)(g) requires that information anyway, so a closed internal channel
 * still tells the reporter where to go.
 *
 * <p>Successful reads are cached like {@code EthicsEntitlements} (same TTL reasoning); a
 * failed read never extends or creates an entry, so after an outage the next successful
 * read re-establishes the truth rather than a stale one surviving indefinitely.
 */
@Component
public class IntakeChannelGate {

    private static final Logger log = LoggerFactory.getLogger(IntakeChannelGate.class);

    static final Duration TTL = Duration.ofMinutes(10);

    private record Entry(boolean open, Instant expiresAt) {}

    private final OrgSubscriptionRepository subscriptions;
    private final Duration grace;
    private final Clock clock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public IntakeChannelGate(
            OrgSubscriptionRepository subscriptions,
            @Value("${ethics.intake.lapse-grace-days:14}") int graceDays) {
        this(subscriptions, graceDays, Clock.systemUTC());
    }

    /** Package-private so a test can fix the instant; there is no Clock bean to override. */
    IntakeChannelGate(OrgSubscriptionRepository subscriptions, int graceDays, Clock clock) {
        if (graceDays < 0) {
            throw new IllegalArgumentException("ethics.intake.lapse-grace-days must be >= 0");
        }
        this.subscriptions = subscriptions;
        this.grace = Duration.ofDays(graceDays);
        this.clock = clock;
    }

    /** Whether new-report intake is open for this organisation. Never closes on doubt. */
    public boolean isOpen(UUID orgId) {
        if (orgId == null) return true; // resolution problems are not the reporter's problem
        Instant now = clock.instant();
        Entry cached = cache.get(orgId);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.open();
        }
        try {
            boolean open = establish(orgId, now);
            cache.put(orgId, new Entry(open, now.plus(TTL)));
            return open;
        } catch (RuntimeException e) {
            // Fail OPEN, and do not cache: the outage must not close the channel, and it
            // must also not mint a long-lived "open" that outlives an actual lapse.
            log.warn("Etik Speak: subscription store unreadable, keeping intake open", e);
            return true;
        }
    }

    private boolean establish(UUID orgId, Instant now) {
        if (!subscriptions.findAllByOrgIdAndActiveTrue(orgId).isEmpty()) {
            return true;
        }
        List<OrgSubscription> all = subscriptions.findAllByOrgId(orgId);
        if (all.isEmpty()) {
            return true; // never subscribed — nothing lapsed
        }
        Instant newestRevocation = all.stream()
                .map(OrgSubscription::getRevokedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (newestRevocation == null) {
            // Rows exist, none active, none carries a revocation timestamp. Not a shape the
            // subscription writer produces; refuse to treat a malformed history as a lapse.
            log.warn("Etik Speak: org {} has inactive subscriptions without revoked_at; keeping intake open", orgId);
            return true;
        }
        return newestRevocation.plus(grace).isAfter(now);
    }

    /** Drops the cached answer for one organisation. For use after a subscription change. */
    public void invalidate(UUID orgId) {
        if (orgId != null) cache.remove(orgId);
    }
}
