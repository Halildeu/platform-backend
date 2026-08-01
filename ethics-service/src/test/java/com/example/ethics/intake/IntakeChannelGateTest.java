package com.example.ethics.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.ethics.model.OrgSubscription;
import com.example.ethics.repository.OrgSubscriptionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Faz 35 ES-403 (#885) — a lapsed subscription closes only new intake, and only when the
 * lapse is <em>established</em>.
 *
 * <p>The staff-side entitlement check fails closed; this gate fails open. Both directions
 * are asserted here on purpose, because the inversion is the design: an outage must never
 * close the channel a reporter is using, and only a positively-known lapse beyond grace may.
 */
class IntakeChannelGateTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    private final OrgSubscriptionRepository repo = mock(OrgSubscriptionRepository.class);

    private IntakeChannelGate gate(int graceDays) {
        return new IntakeChannelGate(repo, graceDays, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OrgSubscription revoked(Instant revokedAt) {
        OrgSubscription s = mock(OrgSubscription.class);
        when(s.getRevokedAt()).thenReturn(revokedAt);
        return s;
    }

    @Test
    @DisplayName("aktif abonelik → açık")
    void activeSubscriptionOpens() {
        when(repo.findAllByOrgIdAndActiveTrue(ORG)).thenReturn(List.of(mock(OrgSubscription.class)));
        assertThat(gate(14).isOpen(ORG)).isTrue();
    }

    @Test
    @DisplayName("hiç abonelik olmamış → açık (düşüş yok ki kapansın)")
    void neverSubscribedStaysOpen() {
        when(repo.findAllByOrgIdAndActiveTrue(ORG)).thenReturn(List.of());
        when(repo.findAllByOrgId(ORG)).thenReturn(List.of());
        assertThat(gate(14).isOpen(ORG)).isTrue();
    }

    @Test
    @DisplayName("grace penceresi içindeki düşüş → hâlâ açık")
    void lapseWithinGraceStaysOpen() {
        OrgSubscription withinGrace = revoked(NOW.minus(Duration.ofDays(13)));
        when(repo.findAllByOrgIdAndActiveTrue(ORG)).thenReturn(List.of());
        when(repo.findAllByOrgId(ORG)).thenReturn(List.of(withinGrace));
        assertThat(gate(14).isOpen(ORG)).isTrue();
    }

    @Test
    @DisplayName("grace aşılmış düşüş → kapalı; yalnız bu durum kapatır")
    void establishedLapseBeyondGraceCloses() {
        OrgSubscription beyondGrace = revoked(NOW.minus(Duration.ofDays(15)));
        when(repo.findAllByOrgIdAndActiveTrue(ORG)).thenReturn(List.of());
        when(repo.findAllByOrgId(ORG)).thenReturn(List.of(beyondGrace));
        assertThat(gate(14).isOpen(ORG)).isFalse();
    }

    /** Re-grant then re-revoke: the NEWEST revocation starts the grace clock, not the first. */
    @Test
    @DisplayName("en yeni revocation grace saatini başlatır, ilki değil")
    void newestRevocationGovernsGrace() {
        OrgSubscription first = revoked(NOW.minus(Duration.ofDays(400)));
        OrgSubscription regrant = revoked(NOW.minus(Duration.ofDays(3)));
        when(repo.findAllByOrgIdAndActiveTrue(ORG)).thenReturn(List.of());
        when(repo.findAllByOrgId(ORG)).thenReturn(List.of(first, regrant));
        assertThat(gate(14).isOpen(ORG)).isTrue();
    }

    @Test
    @DisplayName("store okunamıyor → AÇIK (fail-open) ve cache'e yazılmaz")
    void unreadableStoreFailsOpenWithoutCaching() {
        when(repo.findAllByOrgIdAndActiveTrue(ORG)).thenThrow(new RuntimeException("db down"));
        IntakeChannelGate gate = gate(14);
        assertThat(gate.isOpen(ORG)).isTrue();
        // Second call hits the store again — the outage answer was not cached, so a real
        // lapse becomes visible on the very next successful read.
        assertThat(gate.isOpen(ORG)).isTrue();
        verify(repo, org.mockito.Mockito.times(2)).findAllByOrgIdAndActiveTrue(ORG);
    }

    @Test
    @DisplayName("başarılı cevap TTL boyunca cache'lenir")
    void successfulAnswerIsCached() {
        when(repo.findAllByOrgIdAndActiveTrue(ORG)).thenReturn(List.of(mock(OrgSubscription.class)));
        IntakeChannelGate gate = gate(14);
        assertThat(gate.isOpen(ORG)).isTrue();
        assertThat(gate.isOpen(ORG)).isTrue();
        verify(repo, org.mockito.Mockito.times(1)).findAllByOrgIdAndActiveTrue(ORG);
    }

    @Test
    @DisplayName("invalidate sonrası cevap yeniden kurulur (yeniden grant akışı)")
    void invalidateReestablishes() {
        OrgSubscription lapsed = revoked(NOW.minus(Duration.ofDays(30)));
        when(repo.findAllByOrgIdAndActiveTrue(ORG)).thenReturn(List.of());
        when(repo.findAllByOrgId(ORG)).thenReturn(List.of(lapsed));
        IntakeChannelGate gate = gate(14);
        assertThat(gate.isOpen(ORG)).isFalse();
        when(repo.findAllByOrgIdAndActiveTrue(ORG)).thenReturn(List.of(mock(OrgSubscription.class)));
        gate.invalidate(ORG);
        assertThat(gate.isOpen(ORG)).isTrue();
    }

    @Test
    @DisplayName("revoked_at'siz pasif satırlar (bozuk tarih) düşüş SAYILMAZ → açık")
    void malformedHistoryWithoutRevocationTimestampStaysOpen() {
        OrgSubscription noTimestamp = revoked(null);
        when(repo.findAllByOrgIdAndActiveTrue(ORG)).thenReturn(List.of());
        when(repo.findAllByOrgId(ORG)).thenReturn(List.of(noTimestamp));
        assertThat(gate(14).isOpen(ORG)).isTrue();
    }

    @Test
    @DisplayName("çözülemeyen org (null) ihbarcının sorunu değildir → açık, store'a sorulmaz")
    void nullOrgOpensWithoutTouchingStore() {
        assertThat(gate(14).isOpen(null)).isTrue();
        verifyNoMoreInteractions(repo);
    }

    @Test
    @DisplayName("negatif grace konfigürasyonu açılışta düşer")
    void negativeGraceFailsAtConstruction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gate(-1))
                .hasMessageContaining("lapse-grace-days");
    }

    /**
     * <strong>The scope boundary, asserted structurally.</strong> The owner decision names
     * exactly one closable surface: new-report intake. The gate must therefore be consulted
     * by {@code createReport} and by nothing else — a second call site would silently widen
     * "intake closes" into "the product closes", which is the outcome the decision refused.
     * Source-text assertion, because the danger is a future edit far from this file.
     */
    @Test
    @DisplayName("kapı yalnız createReport'ta sorulur — ikinci çağrı yeri kapsam genişletir")
    void theGateIsConsultedOnlyByCreateReport() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/com/example/ethics/service/EthicsService.java"));
        long callSites = service.split("intakeChannel\\.isOpen", -1).length - 1;
        assertThat(callSites)
                .as("intakeChannel.isOpen çağrı sayısı — yalnız createReport'ta olmalı")
                .isEqualTo(1);
        String beforeMailbox = service.substring(0, service.indexOf("public SessionGrant openMailbox"));
        assertThat(beforeMailbox)
                .as("kapı createReport bölgesinde (openMailbox'tan önce) olmalı")
                .contains("intakeChannel.isOpen");
    }
}
