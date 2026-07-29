package com.example.ethics.catalog;

import com.example.ethics.repository.OrgSubscriptionRepository;
import com.example.ethics.model.OrgSubscription;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ES-403 — does this organisation hold the capability?
 *
 * <p>Not an authorization check. This answers what the customer bought; whether a given
 * person may act belongs to the authorization model. See {@code EthicsProductCatalog}.
 *
 * <p><strong>Cached and fail-closed, which pull against each other.</strong> The acceptance
 * asks for both, and the two words describe opposite behaviours the moment the store is
 * unreachable: a cache that keeps answering is not failing closed, and a check that denies on
 * every miss is not really cached. The resolution here:
 *
 * <ul>
 *   <li>A successful read is cached for {@link #TTL}. Repeated staff requests do not each
 *       reach the database.
 *   <li>A failed read <strong>does not extend</strong> an existing entry and does not create
 *       one. The cached answer ages out on its own schedule and the next question after that
 *       is answered {@code false}.
 * </ul>
 *
 * <p>So an outage is survivable for up to one TTL and then closes, rather than either
 * breaking the product instantly or keeping a capability alive indefinitely on a stale read.
 * The alternative — refreshing the entry's expiry on failure — would turn a database that
 * stays down into a capability that never expires, which is the failure mode where an
 * organisation keeps {@code SUBJECT_REVEAL} because nobody noticed the store was gone.
 *
 * <p>An entitlement outage never reaches the reporter: public intake does not consult this
 * class, and {@code CatalogBoundaryTest} keeps it that way.
 */
@Component
public class EthicsEntitlements {

    private static final Logger log = LoggerFactory.getLogger(EthicsEntitlements.class);

    /** Short enough that a revoked product stops working the same day, long enough to matter. */
    static final Duration TTL = Duration.ofMinutes(10);

    private record Entry(Set<String> productIds, Instant expiresAt) {}

    private final OrgSubscriptionRepository subscriptions;
    private final EthicsProductCatalog catalog;
    private final Clock clock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public EthicsEntitlements(OrgSubscriptionRepository subscriptions, EthicsProductCatalog catalog) {
        this(subscriptions, catalog, Clock.systemUTC());
    }

    /** Package-private so a test can fix the instant; there is no Clock bean to override. */
    EthicsEntitlements(OrgSubscriptionRepository subscriptions, EthicsProductCatalog catalog, Clock clock) {
        this.subscriptions = subscriptions;
        this.catalog = catalog;
        this.clock = clock;
    }

    public boolean has(UUID orgId, EthicsCapability capability) {
        if (orgId == null || capability == null) return false;
        return catalog.carries(holding(orgId).productIds(), capability);
    }

    /**
     * What the organisation holds, and whether that answer could actually be established.
     *
     * <p>The enforcement path ({@link #has}) treats an unreadable store as "no" and says
     * nothing further — that is the safe direction when deciding whether to open a feature.
     * A screen that shows the customer their own subscription needs the other distinction:
     * rendering "you hold nothing" during an outage is not fail-closed, it is wrong, and it
     * invites someone to buy what they already own or to report a capability as revoked.
     *
     * <p>So the enforcement answer stays closed while the informational answer can say
     * "unknown". The flag names only the confidence, never the dependency or its health.
     */
    public Holding holding(UUID orgId) {
        if (orgId == null) return new Holding(Set.of(), Set.of(), true);
        Instant now = clock.instant();
        Entry cached = cache.get(orgId);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return resolved(cached.productIds(), true);
        }
        try {
            Set<String> products = subscriptions.findAllByOrgIdAndActiveTrue(orgId).stream()
                    .map(OrgSubscription::getProductId)
                    .collect(Collectors.toUnmodifiableSet());
            cache.put(orgId, new Entry(products, now.plus(TTL)));
            return resolved(products, true);
        } catch (RuntimeException e) {
            // Deliberately no cache write, not even to extend what is already there. A failed
            // read must not buy the entry more time; otherwise a store that stays down keeps
            // every capability alive forever.
            log.warn("Etik Speak: entitlement store unreadable, answering closed", e);
            return new Holding(Set.of(), Set.of(), false);
        }
    }

    private Holding resolved(Set<String> productIds, boolean authoritative) {
        Set<EthicsCapability> capabilities = java.util.Arrays.stream(EthicsCapability.values())
                .filter(c -> catalog.carries(productIds, c))
                .collect(Collectors.toCollection(() -> java.util.EnumSet.noneOf(EthicsCapability.class)));
        return new Holding(productIds, Set.copyOf(capabilities), authoritative);
    }

    /**
     * @param authoritative false only when the store could not be read and no unexpired
     *     cached answer existed — the products and capabilities are then empty because
     *     nothing could be established, not because nothing was bought.
     */
    public record Holding(
            Set<String> productIds, Set<EthicsCapability> capabilities, boolean authoritative) {}

    /** Drops the cached answer for one organisation. For use after a subscription change. */
    public void invalidate(UUID orgId) {
        if (orgId != null) cache.remove(orgId);
    }
}
