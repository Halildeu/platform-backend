package com.serban.notify.push;

import com.serban.notify.api.dto.PushEndpointListResponse;
import com.serban.notify.api.dto.PushSubscribeRequest;
import com.serban.notify.api.dto.PushSubscribeResponse;
import com.serban.notify.domain.SubscriberPushEndpoint;
import com.serban.notify.exception.InvalidRequestException;
import com.serban.notify.repository.SubscriberPushEndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PushSubscriptionService unit test (Faz 23.7 M7 T4.2 PR-W3).
 *
 * <p>Mockito-based; gerçek DB yok. PG IT ayrı dosyada (PR-W3 follow-up).
 */
class PushSubscriptionServiceTest {

    private SubscriberPushEndpointRepository repo;
    private PushSubscriptionService service;

    private static final String ORG = "acme";
    private static final String SUB = "1204";

    /** Valid uncompressed P-256 public key (65 bytes; first byte 0x04). */
    private static final String VALID_P256DH;
    /** Valid 16-byte auth secret. */
    private static final String VALID_AUTH_SECRET;

    static {
        byte[] pub = new byte[65];
        pub[0] = 0x04;
        // X, Y coordinates: deterministic but non-trivial test values
        // (validator only checks length + prefix; not curve membership).
        for (int i = 1; i < 65; i++) {
            pub[i] = (byte) (i & 0xFF);
        }
        VALID_P256DH = Base64.getUrlEncoder().withoutPadding().encodeToString(pub);

        byte[] auth = new byte[16];
        for (int i = 0; i < 16; i++) {
            auth[i] = (byte) (0x10 + i);
        }
        VALID_AUTH_SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(auth);
    }

    private static PushSubscribeRequest validRequest(String endpointUrl, String userAgent) {
        return new PushSubscribeRequest(endpointUrl, VALID_P256DH, VALID_AUTH_SECRET, userAgent);
    }

    @BeforeEach
    void setUp() {
        repo = mock(SubscriberPushEndpointRepository.class);
        service = new PushSubscriptionService(repo);
    }

    @Test
    void subscribeCreatesNewEndpointWhenNotExisting() {
        when(repo.findByOrgIdAndSubscriberIdAndEndpointUrl(eq(ORG), eq(SUB), any()))
            .thenReturn(Optional.empty());
        when(repo.saveAndFlush(any(SubscriberPushEndpoint.class)))
            .thenAnswer(inv -> {
                SubscriberPushEndpoint e = inv.getArgument(0);
                if (e.getEndpointId() == null) {
                    e.setEndpointId(UUID.randomUUID());
                }
                return e;
            });

        PushSubscribeRequest req = validRequest(
            "https://fcm.googleapis.com/fcm/send/token", "Mozilla/5.0 Chrome"
        );

        PushSubscribeResponse response = service.subscribe(ORG, SUB, req);

        assertThat(response.status()).isEqualTo("created");
        assertThat(response.endpointId()).isNotNull();
        verify(repo).saveAndFlush(any(SubscriberPushEndpoint.class));
    }

    @Test
    void subscribeUpdatesKeysWhenEndpointExists() {
        SubscriberPushEndpoint existing = new SubscriberPushEndpoint();
        UUID id = UUID.randomUUID();
        existing.setEndpointId(id);
        existing.setOrgId(ORG);
        existing.setSubscriberId(SUB);
        existing.setEndpointUrl("https://fcm.googleapis.com/fcm/send/token");
        existing.setP256dhKey("old-p256dh");
        existing.setAuthSecret("old-auth");
        existing.setDeletedAt(null);
        when(repo.findByOrgIdAndSubscriberIdAndEndpointUrl(eq(ORG), eq(SUB), any()))
            .thenReturn(Optional.of(existing));
        when(repo.save(any(SubscriberPushEndpoint.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        PushSubscribeRequest req = validRequest(
            "https://fcm.googleapis.com/fcm/send/token", null
        );

        PushSubscribeResponse response = service.subscribe(ORG, SUB, req);

        assertThat(response.status()).isEqualTo("updated");
        assertThat(response.endpointId()).isEqualTo(id);
        assertThat(existing.getP256dhKey()).isEqualTo(VALID_P256DH);
        assertThat(existing.getAuthSecret()).isEqualTo(VALID_AUTH_SECRET);
        assertThat(existing.getDeletedAt()).isNull();
    }

    @Test
    void subscribeReactivatesSoftDeletedEndpoint() {
        SubscriberPushEndpoint deleted = new SubscriberPushEndpoint();
        UUID id = UUID.randomUUID();
        deleted.setEndpointId(id);
        deleted.setOrgId(ORG);
        deleted.setSubscriberId(SUB);
        deleted.setEndpointUrl("https://fcm.googleapis.com/fcm/send/token");
        deleted.setDeletedAt(OffsetDateTime.now().minusHours(2));
        deleted.setFailureCount(5);
        deleted.setLastFailureReason("410_GONE");
        when(repo.findByOrgIdAndSubscriberIdAndEndpointUrl(eq(ORG), eq(SUB), any()))
            .thenReturn(Optional.of(deleted));
        when(repo.save(any(SubscriberPushEndpoint.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        PushSubscribeRequest req = validRequest(
            "https://fcm.googleapis.com/fcm/send/token", null
        );

        PushSubscribeResponse response = service.subscribe(ORG, SUB, req);

        assertThat(response.status()).isEqualTo("reactivated");
        assertThat(deleted.getDeletedAt()).isNull();
        assertThat(deleted.getFailureCount()).isZero();
        assertThat(deleted.getLastFailureReason()).isNull();
    }

    @Test
    void subscribeRaceRecoveryReReadsAndUpdates() {
        // Codex 019e4a57 P1 absorb: paralel iki ilk subscribe race,
        // saveAndFlush DataIntegrityViolationException atar; service
        // re-read + update path'e düşer.
        when(repo.findByOrgIdAndSubscriberIdAndEndpointUrl(eq(ORG), eq(SUB), any()))
            .thenReturn(Optional.empty())  // initial empty (race start)
            .thenAnswer(inv -> {
                // Race winner inserted; we see the row now
                SubscriberPushEndpoint winner = new SubscriberPushEndpoint();
                winner.setEndpointId(UUID.fromString(
                    "99999999-aaaa-bbbb-cccc-dddddddddddd"));
                winner.setOrgId(ORG);
                winner.setSubscriberId(SUB);
                winner.setEndpointUrl(inv.getArgument(2));
                winner.setP256dhKey("race-winner-p");
                winner.setAuthSecret("race-winner-a");
                winner.setDeletedAt(null);
                return Optional.of(winner);
            });
        when(repo.saveAndFlush(any(SubscriberPushEndpoint.class)))
            .thenThrow(new DataIntegrityViolationException("unique violation"));
        when(repo.save(any(SubscriberPushEndpoint.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        PushSubscribeRequest req = validRequest(
            "https://fcm.googleapis.com/fcm/send/race", null
        );

        PushSubscribeResponse response = service.subscribe(ORG, SUB, req);

        assertThat(response.status()).isEqualTo("updated");
        assertThat(response.endpointId()).isEqualTo(
            UUID.fromString("99999999-aaaa-bbbb-cccc-dddddddddddd"));
    }

    @Test
    void subscribeRejectsHttpScheme() {
        PushSubscribeRequest req = new PushSubscribeRequest(
            "http://insecure.example/push", VALID_P256DH, VALID_AUTH_SECRET, null
        );
        // DTO @Pattern would reject http:// at the controller layer
        // before reaching service; but PushSubscriptionMaterialValidator
        // also guards here (defense-in-depth + non-controller callers).
        assertThatThrownBy(() -> service.subscribe(ORG, SUB, req))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("https");
    }

    @Test
    void subscribeRejectsInvalidP256dhLength() {
        // 32 bytes (compressed-ish length) instead of 65 — invalid
        byte[] shortKey = new byte[32];
        shortKey[0] = 0x04;
        String shortB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(shortKey);

        PushSubscribeRequest req = new PushSubscribeRequest(
            "https://fcm.googleapis.com/fcm/send/x",
            shortB64, VALID_AUTH_SECRET, null
        );

        assertThatThrownBy(() -> service.subscribe(ORG, SUB, req))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("p256dhKey");
    }

    @Test
    void subscribeRejectsInvalidP256dhPrefix() {
        // 65 bytes but first byte != 0x04 — invalid uncompressed prefix
        byte[] badPrefix = new byte[65];
        badPrefix[0] = 0x02;  // compressed prefix — Web Push expects uncompressed
        String badPrefixB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(badPrefix);

        PushSubscribeRequest req = new PushSubscribeRequest(
            "https://fcm.googleapis.com/fcm/send/x",
            badPrefixB64, VALID_AUTH_SECRET, null
        );

        assertThatThrownBy(() -> service.subscribe(ORG, SUB, req))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("0x04");
    }

    @Test
    void subscribeRejectsInvalidAuthSecretLength() {
        // 8 bytes instead of 16 — invalid
        byte[] shortAuth = new byte[8];
        String shortB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(shortAuth);

        PushSubscribeRequest req = new PushSubscribeRequest(
            "https://fcm.googleapis.com/fcm/send/x",
            VALID_P256DH, shortB64, null
        );

        assertThatThrownBy(() -> service.subscribe(ORG, SUB, req))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("authSecret");
    }

    @Test
    void listActiveReturnsMinimalProjection() {
        SubscriberPushEndpoint e1 = new SubscriberPushEndpoint();
        UUID id1 = UUID.randomUUID();
        e1.setEndpointId(id1);
        e1.setUserAgent("Mozilla/5.0 Chrome");
        e1.setPlatformHint("Chrome");
        e1.setCreatedAt(OffsetDateTime.now().minusDays(2));
        e1.setLastSeenAt(OffsetDateTime.now().minusMinutes(5));
        // raw endpoint URL/keys should NOT leak into response
        e1.setEndpointUrl("https://fcm.googleapis.com/fcm/send/secret-token");
        e1.setP256dhKey("secret-p");
        e1.setAuthSecret("secret-a");

        when(repo.findActiveBySubscriber(ORG, SUB)).thenReturn(List.of(e1));

        PushEndpointListResponse response = service.listActive(ORG, SUB);

        assertThat(response.endpoints()).hasSize(1);
        PushEndpointListResponse.Endpoint e = response.endpoints().get(0);
        assertThat(e.endpointId()).isEqualTo(id1);
        assertThat(e.userAgent()).isEqualTo("Mozilla/5.0 Chrome");
        assertThat(e.platformHint()).isEqualTo("Chrome");
        // Endpoint record has no endpointUrl / p256dh / authSecret fields by design
    }

    @Test
    void unsubscribeSucceedsWhenOwnedByCaller() {
        UUID id = UUID.randomUUID();
        SubscriberPushEndpoint endpoint = new SubscriberPushEndpoint();
        endpoint.setEndpointId(id);
        endpoint.setOrgId(ORG);
        endpoint.setSubscriberId(SUB);
        endpoint.setDeletedAt(null);
        when(repo.findById(id)).thenReturn(Optional.of(endpoint));
        when(repo.softDeleteByEndpointId(eq(id), any(OffsetDateTime.class))).thenReturn(1);

        boolean result = service.unsubscribe(ORG, SUB, id);

        assertThat(result).isTrue();
        verify(repo).softDeleteByEndpointId(eq(id), any(OffsetDateTime.class));
    }

    @Test
    void unsubscribeDeniedWhenCrossSubscriber() {
        // Endpoint sub-OTHER'a ait; SUB silmek istiyor → deny
        UUID id = UUID.randomUUID();
        SubscriberPushEndpoint endpoint = new SubscriberPushEndpoint();
        endpoint.setEndpointId(id);
        endpoint.setOrgId(ORG);
        endpoint.setSubscriberId("sub-OTHER");
        endpoint.setDeletedAt(null);
        when(repo.findById(id)).thenReturn(Optional.of(endpoint));

        boolean result = service.unsubscribe(ORG, SUB, id);

        assertThat(result).isFalse();
        verify(repo, never()).softDeleteByEndpointId(any(), any());
    }

    @Test
    void unsubscribeDeniedWhenCrossOrg() {
        UUID id = UUID.randomUUID();
        SubscriberPushEndpoint endpoint = new SubscriberPushEndpoint();
        endpoint.setEndpointId(id);
        endpoint.setOrgId("other-org");
        endpoint.setSubscriberId(SUB);
        endpoint.setDeletedAt(null);
        when(repo.findById(id)).thenReturn(Optional.of(endpoint));

        boolean result = service.unsubscribe(ORG, SUB, id);

        assertThat(result).isFalse();
        verify(repo, never()).softDeleteByEndpointId(any(), any());
    }

    @Test
    void unsubscribeNoOpWhenAlreadyDeleted() {
        UUID id = UUID.randomUUID();
        SubscriberPushEndpoint endpoint = new SubscriberPushEndpoint();
        endpoint.setEndpointId(id);
        endpoint.setOrgId(ORG);
        endpoint.setSubscriberId(SUB);
        endpoint.setDeletedAt(OffsetDateTime.now().minusHours(1));
        when(repo.findById(id)).thenReturn(Optional.of(endpoint));

        boolean result = service.unsubscribe(ORG, SUB, id);

        assertThat(result).isFalse();
        verify(repo, never()).softDeleteByEndpointId(any(), any());
    }

    @Test
    void unsubscribeReturnsFalseWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        boolean result = service.unsubscribe(ORG, SUB, id);

        assertThat(result).isFalse();
        verify(repo, never()).softDeleteByEndpointId(any(), any());
    }
}
