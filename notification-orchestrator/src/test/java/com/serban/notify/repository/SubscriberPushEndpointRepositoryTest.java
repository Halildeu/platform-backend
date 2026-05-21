package com.serban.notify.repository;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.SubscriberPushEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SubscriberPushEndpoint repository IT — Faz 23.7 M7 T4.2 PR-W1.
 *
 * <p>V19 migration + JPA mapping + soft-delete + failure counter coverage.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
@org.springframework.transaction.annotation.Transactional
class SubscriberPushEndpointRepositoryTest extends AbstractPostgresTest {

    @Autowired
    SubscriberPushEndpointRepository repo;

    @jakarta.persistence.PersistenceContext
    jakarta.persistence.EntityManager em;

    @Test
    void savePersistsAllFieldsAndAutoGenerates() {
        SubscriberPushEndpoint entry = fixture("acme", "1204", "https://fcm.googleapis.com/fcm/send/AAA");
        SubscriberPushEndpoint saved = repo.save(entry);

        assertThat(saved.getEndpointId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getLastSeenAt()).isNotNull();
        assertThat(saved.getFailureCount()).isZero();
        assertThat(saved.getDeletedAt()).isNull();
    }

    @Test
    void uniqueConstraintOnSubscriberAndEndpointUrl() {
        repo.save(fixture("acme", "1204", "https://fcm.googleapis.com/fcm/send/UNIQ-1"));

        SubscriberPushEndpoint duplicate = fixture("acme", "1204", "https://fcm.googleapis.com/fcm/send/UNIQ-1");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            repo.save(duplicate);
            repo.flush();
        }).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void findActiveBySubscriberExcludesSoftDeleted() {
        SubscriberPushEndpoint active = fixture("acme", "1204", "https://endpoint-a");
        repo.save(active);

        SubscriberPushEndpoint deleted = fixture("acme", "1204", "https://endpoint-b");
        deleted.setDeletedAt(OffsetDateTime.now().minusDays(1));
        repo.save(deleted);

        List<SubscriberPushEndpoint> activeOnly = repo.findActiveBySubscriber("acme", "1204");

        assertThat(activeOnly)
            .extracting(SubscriberPushEndpoint::getEndpointUrl)
            .contains("https://endpoint-a")
            .doesNotContain("https://endpoint-b");
    }

    @Test
    void softDeleteBySubscriberDeactivatesAllEndpoints() {
        repo.save(fixture("acme", "1204", "https://ep-1"));
        repo.save(fixture("acme", "1204", "https://ep-2"));
        repo.save(fixture("acme", "5678", "https://ep-3")); // farklı subscriber — etkilenmez

        int deleted = repo.softDeleteBySubscriber("acme", "1204", OffsetDateTime.now());
        em.flush();
        em.clear();

        assertThat(deleted).isEqualTo(2);
        assertThat(repo.findActiveBySubscriber("acme", "1204")).isEmpty();
        // Farklı subscriber etkilenmedi
        assertThat(repo.findActiveBySubscriber("acme", "5678")).hasSize(1);
    }

    @Test
    void incrementFailureUpdatesCountAndReason() {
        SubscriberPushEndpoint saved = repo.save(fixture("acme", "1204", "https://ep-fail"));

        int updated = repo.incrementFailure(saved.getEndpointId(), OffsetDateTime.now(), "410_GONE");
        em.flush();
        em.clear();

        assertThat(updated).isEqualTo(1);
        SubscriberPushEndpoint refetched = repo.findById(saved.getEndpointId()).orElseThrow();
        assertThat(refetched.getFailureCount()).isEqualTo(1);
        assertThat(refetched.getLastFailureReason()).isEqualTo("410_GONE");
        assertThat(refetched.getLastFailureAt()).isNotNull();
    }

    @Test
    void findByEndpointUrlReturnsExistingRow() {
        repo.save(fixture("acme", "1204", "https://lookup-endpoint"));

        Optional<SubscriberPushEndpoint> found = repo.findByOrgIdAndSubscriberIdAndEndpointUrl(
            "acme", "1204", "https://lookup-endpoint"
        );

        assertThat(found).isPresent();
        assertThat(found.get().getEndpointUrl()).isEqualTo("https://lookup-endpoint");
    }

    // Faz 23.7 M7 T4.2 PR-W3 — endpoint-level soft delete (Codex 019e4a57 P5)
    @Test
    void softDeleteByEndpointIdSoftDeletesSingleEndpoint() {
        SubscriberPushEndpoint ep1 = repo.save(fixture("acme", "1204", "https://ep-chrome"));
        SubscriberPushEndpoint ep2 = repo.save(fixture("acme", "1204", "https://ep-firefox"));
        SubscriberPushEndpoint ep3 = repo.save(fixture("acme", "5678", "https://ep-other"));

        int deleted = repo.softDeleteByEndpointId(ep1.getEndpointId(), OffsetDateTime.now());
        em.flush();
        em.clear();

        assertThat(deleted).isEqualTo(1);
        // Aynı subscriber'ın diğer endpoint'i etkilenmedi (multi-endpoint cihaz boundary)
        List<SubscriberPushEndpoint> active = repo.findActiveBySubscriber("acme", "1204");
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getEndpointId()).isEqualTo(ep2.getEndpointId());
        // Farklı subscriber'ın endpoint'i de etkilenmedi
        assertThat(repo.findActiveBySubscriber("acme", "5678")).hasSize(1);
    }

    @Test
    void softDeleteByEndpointIdIdempotent() {
        SubscriberPushEndpoint ep = repo.save(fixture("acme", "1204", "https://ep-idempotent"));

        int first = repo.softDeleteByEndpointId(ep.getEndpointId(), OffsetDateTime.now());
        em.flush();
        em.clear();
        assertThat(first).isEqualTo(1);

        // İkinci çağrı: zaten silindi → 0 row affected
        int second = repo.softDeleteByEndpointId(ep.getEndpointId(), OffsetDateTime.now());
        em.flush();
        em.clear();
        assertThat(second).isZero();
    }

    @Test
    void softDeleteByEndpointIdNoOpWhenNotFound() {
        java.util.UUID unknownId = java.util.UUID.randomUUID();

        int deleted = repo.softDeleteByEndpointId(unknownId, OffsetDateTime.now());

        assertThat(deleted).isZero();
    }

    // Faz 23.7 M7 T4.2 PR-W3 iter-3 — Atomic upsert PG IT (Codex 019e4a57 iter-2 P1)
    @Test
    void upsertAtomicInsertsNewRow() {
        OffsetDateTime now = OffsetDateTime.now();
        int rows = repo.upsertAtomic(
            "acme", "1204", "https://fcm.googleapis.com/upsert-new",
            "p256dh-new", "auth-new", "Mozilla/5.0",
            now
        );
        em.flush();
        em.clear();

        assertThat(rows).isEqualTo(1);
        Optional<SubscriberPushEndpoint> saved = repo.findByOrgIdAndSubscriberIdAndEndpointUrl(
            "acme", "1204", "https://fcm.googleapis.com/upsert-new"
        );
        assertThat(saved).isPresent();
        assertThat(saved.get().getP256dhKey()).isEqualTo("p256dh-new");
        assertThat(saved.get().getAuthSecret()).isEqualTo("auth-new");
        assertThat(saved.get().getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.get().getDeletedAt()).isNull();
        assertThat(saved.get().getFailureCount()).isZero();
    }

    @Test
    void upsertAtomicUpdatesKeysOnConflict() {
        // İlk insert
        repo.upsertAtomic("acme", "1204", "https://fcm.googleapis.com/upsert-conflict",
            "old-p", "old-a", "Old/UA",
            OffsetDateTime.now());
        em.flush();
        em.clear();

        // İkinci çağrı: aynı (orgId, subscriberId, endpointUrl) → keys güncel
        repo.upsertAtomic("acme", "1204", "https://fcm.googleapis.com/upsert-conflict",
            "new-p", "new-a", "New/UA",
            OffsetDateTime.now());
        em.flush();
        em.clear();

        SubscriberPushEndpoint refetched = repo.findByOrgIdAndSubscriberIdAndEndpointUrl(
            "acme", "1204", "https://fcm.googleapis.com/upsert-conflict"
        ).orElseThrow();
        assertThat(refetched.getP256dhKey()).isEqualTo("new-p");
        assertThat(refetched.getAuthSecret()).isEqualTo("new-a");
        assertThat(refetched.getUserAgent()).isEqualTo("New/UA");
    }

    @Test
    void upsertAtomicReactivatesSoftDeletedRow() {
        // İlk insert
        repo.upsertAtomic("acme", "1204", "https://fcm.googleapis.com/upsert-reactivate",
            "p1", "a1", "UA1",
            OffsetDateTime.now());
        em.flush();
        em.clear();

        // Soft delete + failure counter increment (gerçek runtime simülasyonu)
        SubscriberPushEndpoint row = repo.findByOrgIdAndSubscriberIdAndEndpointUrl(
            "acme", "1204", "https://fcm.googleapis.com/upsert-reactivate"
        ).orElseThrow();
        repo.incrementFailure(row.getEndpointId(), OffsetDateTime.now(), "410_GONE");
        repo.softDeleteByEndpointId(row.getEndpointId(), OffsetDateTime.now());
        em.flush();
        em.clear();

        // Upsert tekrar: deleted_at NULL, failure_count 0, keys güncel
        repo.upsertAtomic("acme", "1204", "https://fcm.googleapis.com/upsert-reactivate",
            "p2", "a2", "UA2",
            OffsetDateTime.now());
        em.flush();
        em.clear();

        SubscriberPushEndpoint refetched = repo.findByOrgIdAndSubscriberIdAndEndpointUrl(
            "acme", "1204", "https://fcm.googleapis.com/upsert-reactivate"
        ).orElseThrow();
        assertThat(refetched.getDeletedAt()).isNull();
        assertThat(refetched.getFailureCount()).isZero();
        assertThat(refetched.getLastFailureReason()).isNull();
        assertThat(refetched.getP256dhKey()).isEqualTo("p2");
    }

    @Test
    void upsertAtomicPreservesUserAgentWhenNullInputCoalesce() {
        // İlk insert: userAgent="UA1"
        repo.upsertAtomic("acme", "1204", "https://fcm.googleapis.com/coalesce",
            "p1", "a1", "UA1",
            OffsetDateTime.now());
        em.flush();
        em.clear();

        // İkinci çağrı: userAgent=NULL → COALESCE ile mevcut "UA1" korunmalı
        repo.upsertAtomic("acme", "1204", "https://fcm.googleapis.com/coalesce",
            "p2", "a2", null,
            OffsetDateTime.now());
        em.flush();
        em.clear();

        SubscriberPushEndpoint refetched = repo.findByOrgIdAndSubscriberIdAndEndpointUrl(
            "acme", "1204", "https://fcm.googleapis.com/coalesce"
        ).orElseThrow();
        // COALESCE EXCLUDED.user_agent (NULL) → mevcut "UA1" korundu
        assertThat(refetched.getUserAgent()).isEqualTo("UA1");
        assertThat(refetched.getP256dhKey()).isEqualTo("p2");
    }

    private SubscriberPushEndpoint fixture(String orgId, String subscriberId, String endpointUrl) {
        SubscriberPushEndpoint e = new SubscriberPushEndpoint();
        e.setOrgId(orgId);
        e.setSubscriberId(subscriberId);
        e.setEndpointUrl(endpointUrl);
        e.setP256dhKey("test-p256dh-key-base64url");
        e.setAuthSecret("test-auth-secret-16b");
        e.setUserAgent("Mozilla/5.0 (Test)");
        e.setPlatformHint("chrome");
        return e;
    }
}
