package com.example.ethics.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ethics.model.OrgSubscription;
import com.example.ethics.repository.OrgSubscriptionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Faz 35 ES-403 — cached and fail-closed, which pull against each other (#885).
 *
 * <p>The acceptance asks for both words, and they describe opposite behaviours the moment the
 * store is unreachable. These tests pin the resolution: a successful read is cached, a failed
 * read neither creates nor extends an entry, so an outage is survivable for at most one TTL
 * and then closes.
 */
class EthicsEntitlementsTest {

    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");
    private static final UUID ORG = UUID.randomUUID();

    private OrgSubscriptionRepository subscriptions;
    private EthicsProductCatalog catalog;

    @BeforeEach
    void setUp() {
        subscriptions = mock(OrgSubscriptionRepository.class);
        catalog = new EthicsProductCatalog();
    }

    private EthicsEntitlements at(Instant now) {
        return new EthicsEntitlements(subscriptions, catalog, Clock.fixed(now, ZoneOffset.UTC));
    }

    private OrgSubscription held(String productId) {
        return new OrgSubscription(UUID.randomUUID(), ORG, productId, NOW.minus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("satın alınan ürünün yeteneği var")
    void aHeldProductGrantsItsCapabilities() {
        when(subscriptions.findAllByOrgIdAndActiveTrue(ORG))
                .thenReturn(List.of(held("etik-speak-core")));

        assertThat(at(NOW).has(ORG, EthicsCapability.EVIDENCE_ATTACHMENTS)).isTrue();
    }

    @Test
    @DisplayName("satın alınmayan yetenek yok")
    void anUnheldCapabilityIsAbsent() {
        when(subscriptions.findAllByOrgIdAndActiveTrue(ORG))
                .thenReturn(List.of(held("etik-speak-core")));

        assertThat(at(NOW).has(ORG, EthicsCapability.SUBJECT_REVEAL)).isFalse();
    }

    /** Repeated staff requests must not each reach the database. */
    @Test
    @DisplayName("başarılı okuma önbelleğe alınır")
    void aSuccessfulReadIsCached() {
        when(subscriptions.findAllByOrgIdAndActiveTrue(ORG))
                .thenReturn(List.of(held("etik-speak-plus")));
        var entitlements = at(NOW);

        entitlements.has(ORG, EthicsCapability.DATA_EXPORT);
        entitlements.has(ORG, EthicsCapability.DATA_EXPORT);
        entitlements.has(ORG, EthicsCapability.MULTI_HOST_INTAKE);

        verify(subscriptions, times(1)).findAllByOrgIdAndActiveTrue(ORG);
    }

    /** Nothing cached and the store is down: the answer is no, not "probably yes". */
    @Test
    @DisplayName("depo erişilemezken ve önbellek boşken cevap kapalı")
    void anOutageWithNoCachedAnswerFailsClosed() {
        when(subscriptions.findAllByOrgIdAndActiveTrue(ORG))
                .thenThrow(new RuntimeException("entitlement store unreachable"));

        assertThat(at(NOW).has(ORG, EthicsCapability.EVIDENCE_ATTACHMENTS)).isFalse();
    }

    /**
     * The property the whole design turns on, tested on one instance with a clock that moves.
     *
     * <p>A failed read must not buy the cached entry more time; otherwise a store that stays
     * down keeps every capability alive forever — including the one nobody wants left open
     * when the system is not being watched.
     *
     * <p>The first version of this test used a fixed clock and a second instance, so its last
     * assertion said "the cache is still valid" while its name promised "it expires and then
     * closes". It passed for the wrong reason.
     */
    @Test
    @DisplayName("başarısız okuma önbelleğin ömrünü uzatmaz: TTL dolunca kapanır")
    void aFailedReadDoesNotExtendTheCachedAnswer() {
        var movable = new MovableClock(NOW);
        var entitlements = new EthicsEntitlements(subscriptions, catalog, movable);

        when(subscriptions.findAllByOrgIdAndActiveTrue(ORG))
                .thenReturn(List.of(held("etik-speak-subject-reveal")));
        assertThat(entitlements.has(ORG, EthicsCapability.SUBJECT_REVEAL)).isTrue();

        when(subscriptions.findAllByOrgIdAndActiveTrue(ORG))
                .thenThrow(new RuntimeException("entitlement store unreachable"));

        // Inside the TTL the cached answer still stands — an outage is survivable.
        movable.advance(Duration.ofMinutes(5));
        assertThat(entitlements.has(ORG, EthicsCapability.SUBJECT_REVEAL))
                .as("TTL içinde önbellek cevabı korunmalı")
                .isTrue();

        // Past the TTL it closes, and every later attempt keeps closing: the failed reads in
        // between must not have refreshed the entry.
        movable.advance(EthicsEntitlements.TTL);
        assertThat(entitlements.has(ORG, EthicsCapability.SUBJECT_REVEAL))
                .as("TTL dolduktan sonra kapanmalı")
                .isFalse();
        movable.advance(Duration.ofMinutes(1));
        assertThat(entitlements.has(ORG, EthicsCapability.SUBJECT_REVEAL)).isFalse();
    }

    /** A clock the test can move, so cache expiry is exercised rather than assumed. */
    private static final class MovableClock extends Clock {
        private Instant now;

        private MovableClock(Instant start) { this.now = start; }

        void advance(Duration by) { this.now = this.now.plus(by); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** A revoked subscription stops working; the row survives, the capability does not. */
    @Test
    @DisplayName("iptal edilen abonelik yeteneği taşımaz")
    void arevokedSubscriptionIsNotHeld() {
        var revoked = held("etik-speak-plus");
        revoked.revoke(NOW);
        assertThat(revoked.isActive()).isFalse();
        assertThat(revoked.getRevokedAt()).isEqualTo(NOW);
        // The repository only returns active rows, so a revoked product never reaches the
        // catalog at all.
        when(subscriptions.findAllByOrgIdAndActiveTrue(ORG)).thenReturn(List.of());

        assertThat(at(NOW).has(ORG, EthicsCapability.DATA_EXPORT)).isFalse();
    }

    /** Revoking twice keeps the first revocation time. */
    @Test
    @DisplayName("iki kez iptal ilk iptali korur")
    void revokingTwiceKeepsTheFirstRevocation() {
        var subscription = held("etik-speak-core");
        subscription.revoke(NOW.minus(Duration.ofDays(2)));
        subscription.revoke(NOW);
        assertThat(subscription.getRevokedAt()).isEqualTo(NOW.minus(Duration.ofDays(2)));
    }

    @Test
    @DisplayName("geçersiz girdi depoya hiç gitmez")
    void invalidInputNeverReachesTheStore() {
        var entitlements = at(NOW);
        assertThat(entitlements.has(null, EthicsCapability.DATA_EXPORT)).isFalse();
        assertThat(entitlements.has(ORG, null)).isFalse();
        verify(subscriptions, never()).findAllByOrgIdAndActiveTrue(any());
    }

    /**
     * The distinction the informational view exists for: an unreadable store must not be
     * reported to the customer as "you hold nothing".
     *
     * <p>Enforcement stays closed either way — {@link EthicsEntitlements#has} answers false —
     * but a screen that renders "no subscription" during an outage is not being careful, it is
     * being wrong, and it invites someone to re-buy what they already own or to report a
     * capability as revoked.
     */
    @Test
    @DisplayName("depo okunamazken cevap boş DEĞİL, belirsiz olarak işaretlenir")
    void anUnreadableStoreIsReportedAsUnknownRatherThanEmpty() {
        when(subscriptions.findAllByOrgIdAndActiveTrue(ORG))
                .thenThrow(new RuntimeException("entitlement store unreachable"));
        var entitlements = at(NOW);

        var holding = entitlements.holding(ORG);

        assertThat(holding.authoritative()).as("kesinlik iddiası kaldı").isFalse();
        assertThat(holding.productIds()).isEmpty();
        assertThat(holding.capabilities()).isEmpty();
        // The enforcement path is unaffected: still closed.
        assertThat(entitlements.has(ORG, EthicsCapability.EVIDENCE_ATTACHMENTS)).isFalse();
    }

    /** Within the TTL a cached answer is still an answer, outage or not. */
    @Test
    @DisplayName("TTL içinde önbellekli cevap kesin sayılır")
    void aCachedAnswerStaysAuthoritativeDuringAnOutage() {
        var movable = new MovableClock(NOW);
        var entitlements = new EthicsEntitlements(subscriptions, catalog, movable);
        when(subscriptions.findAllByOrgIdAndActiveTrue(ORG))
                .thenReturn(List.of(held("etik-speak-plus")));
        assertThat(entitlements.holding(ORG).authoritative()).isTrue();

        when(subscriptions.findAllByOrgIdAndActiveTrue(ORG))
                .thenThrow(new RuntimeException("entitlement store unreachable"));
        movable.advance(Duration.ofMinutes(5));

        var holding = entitlements.holding(ORG);
        assertThat(holding.authoritative()).isTrue();
        assertThat(holding.productIds()).containsExactly("etik-speak-plus");
    }

    /** What the customer is shown is derived from the catalog, not stored per organisation. */
    @Test
    @DisplayName("yetenekler üründen türetilir")
    void capabilitiesAreDerivedFromTheProduct() {
        when(subscriptions.findAllByOrgIdAndActiveTrue(ORG))
                .thenReturn(List.of(held("etik-speak-core")));

        assertThat(at(NOW).holding(ORG).capabilities())
                .containsExactlyInAnyOrder(
                        EthicsCapability.EVIDENCE_ATTACHMENTS, EthicsCapability.SLA_NOTIFICATIONS);
    }
}
