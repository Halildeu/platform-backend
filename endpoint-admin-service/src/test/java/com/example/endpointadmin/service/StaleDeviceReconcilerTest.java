package com.example.endpointadmin.service;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaleDeviceReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-07-11T20:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final EndpointDeviceRepository repo = mock(EndpointDeviceRepository.class);

    private StaleDeviceReconciler reconciler(Duration stale, Duration offline) {
        return new StaleDeviceReconciler(repo, clock, stale, offline);
    }

    @Test
    void demotesOfflineBeforeStale_withCorrectThresholds() {
        when(repo.demoteStaleDevices(eq(DeviceStatus.STALE), eq(DeviceStatus.OFFLINE),
                eq(NOW.minus(Duration.ofMinutes(30))))).thenReturn(2);
        when(repo.demoteStaleDevices(eq(DeviceStatus.ONLINE), eq(DeviceStatus.STALE),
                eq(NOW.minus(Duration.ofMinutes(5))))).thenReturn(3);

        StaleDeviceReconciler.ReconcileResult result =
                reconciler(Duration.ofMinutes(5), Duration.ofMinutes(30)).reconcile();

        assertThat(result.markedStale()).isEqualTo(3);
        assertThat(result.markedOffline()).isEqualTo(2);

        // STALE->OFFLINE must run before ONLINE->STALE so a freshly-stale device
        // dwells in STALE for at least one cycle before OFFLINE.
        InOrder order = inOrder(repo);
        order.verify(repo).demoteStaleDevices(eq(DeviceStatus.STALE), eq(DeviceStatus.OFFLINE),
                eq(NOW.minus(Duration.ofMinutes(30))));
        order.verify(repo).demoteStaleDevices(eq(DeviceStatus.ONLINE), eq(DeviceStatus.STALE),
                eq(NOW.minus(Duration.ofMinutes(5))));
    }

    @Test
    void nothingStale_isNoOp() {
        when(repo.demoteStaleDevices(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(0);
        StaleDeviceReconciler.ReconcileResult result =
                reconciler(Duration.ofMinutes(5), Duration.ofMinutes(30)).reconcile();
        assertThat(result.markedStale()).isZero();
        assertThat(result.markedOffline()).isZero();
    }

    @Test
    void nonPositiveStaleTtl_fallsBackToDefault_notDemotingLiveDevices() {
        // A zero/negative stale TTL would demote live devices (threshold == now).
        // Fail-safe pins it to the 5m default -> threshold is now-5m.
        reconciler(Duration.ZERO, Duration.ofMinutes(30)).reconcile();
        org.mockito.Mockito.verify(repo).demoteStaleDevices(
                eq(DeviceStatus.ONLINE), eq(DeviceStatus.STALE), eq(NOW.minus(Duration.ofMinutes(5))));
    }

    @Test
    void offlineTtlBelowStaleTtl_isClampedUpToStaleTtl() {
        // offline must never fire before stale; an inverted config is clamped so
        // offline threshold >= stale threshold (here both become now-10m).
        reconciler(Duration.ofMinutes(10), Duration.ofMinutes(2)).reconcile();
        org.mockito.Mockito.verify(repo).demoteStaleDevices(
                eq(DeviceStatus.STALE), eq(DeviceStatus.OFFLINE), eq(NOW.minus(Duration.ofMinutes(10))));
        org.mockito.Mockito.verify(repo).demoteStaleDevices(
                eq(DeviceStatus.ONLINE), eq(DeviceStatus.STALE), eq(NOW.minus(Duration.ofMinutes(10))));
    }

    @Test
    void nullTtls_useDefaults() {
        reconciler(null, null).reconcile();
        org.mockito.Mockito.verify(repo).demoteStaleDevices(
                eq(DeviceStatus.STALE), eq(DeviceStatus.OFFLINE), eq(NOW.minus(Duration.ofMinutes(30))));
        org.mockito.Mockito.verify(repo).demoteStaleDevices(
                eq(DeviceStatus.ONLINE), eq(DeviceStatus.STALE), eq(NOW.minus(Duration.ofMinutes(5))));
    }
}
