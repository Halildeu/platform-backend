package com.example.endpointadmin.service;

import com.example.endpointadmin.config.ConditionalOnPrimaryEndpointPlane;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Faz 22.6 #2290 — device-staleness reconciler.
 *
 * <p><b>Silent-failure class this closes.</b> The heartbeat handler
 * ({@link EndpointHeartbeatService}) sets a device {@code ONLINE} + stamps
 * {@code lastSeenAt} on every heartbeat, but nothing ever demotes it. When the
 * heartbeat lane stops — the device powers off, loses network, or its machine
 * cert channel breaks — the row stays {@code status=ONLINE} forever and only
 * {@code lastSeenAt} freezes. The fleet dashboard then reports a false-ONLINE
 * device indefinitely, and downstream freshness gates (e.g. {@code UPDATE_AGENT}
 * dispatch 424 on stale heartbeat) fail invisibly.
 *
 * <p><b>Transition.</b> Two-stage, run on a fixed schedule:
 * <ol>
 *   <li>{@code STALE -> OFFLINE} when {@code lastSeenAt} is older than
 *       {@code offline-ttl} (default 30m);</li>
 *   <li>{@code ONLINE -> STALE} when {@code lastSeenAt} is older than
 *       {@code stale-ttl} (default 5m — the platform's established
 *       {@code heartbeat-freshness-ttl}).</li>
 * </ol>
 * OFFLINE runs first so a freshly-stale device dwells in {@code STALE} for at
 * least one cycle before {@code OFFLINE} (a graded signal, not a cliff). The
 * demotion is one-way and self-heals: a returning heartbeat sets the device
 * back to {@code ONLINE}. Terminal/pre-lifecycle states
 * ({@code DECOMMISSIONED}, {@code PENDING_ENROLLMENT}) are never touched.
 *
 * <p>Runs only on the primary endpoint plane (single writer; not the read
 * replica / prod-mirror plane) via {@link ConditionalOnPrimaryEndpointPlane}.
 */
@Component
@ConditionalOnPrimaryEndpointPlane
public class StaleDeviceReconciler {

    private static final Logger log = LoggerFactory.getLogger(StaleDeviceReconciler.class);
    private static final Duration DEFAULT_STALE_TTL = Duration.ofMinutes(5);
    private static final Duration DEFAULT_OFFLINE_TTL = Duration.ofMinutes(30);

    private final EndpointDeviceRepository deviceRepository;
    private final Clock clock;
    private final Duration staleTtl;
    private final Duration offlineTtl;

    public StaleDeviceReconciler(
            EndpointDeviceRepository deviceRepository,
            Clock clock,
            @Value("${endpoint-admin.device-staleness.stale-ttl:PT5M}") Duration staleTtl,
            @Value("${endpoint-admin.device-staleness.offline-ttl:PT30M}") Duration offlineTtl) {
        this.deviceRepository = deviceRepository;
        this.clock = clock;
        // Fail-safe: never let a mis-config drive a non-positive or inverted TTL. A
        // non-positive TTL would demote live devices; offline must be >= stale so the
        // OFFLINE tier can never fire before the STALE tier.
        this.staleTtl = (staleTtl == null || staleTtl.isNegative() || staleTtl.isZero())
                ? DEFAULT_STALE_TTL : staleTtl;
        Duration off = (offlineTtl == null || offlineTtl.isNegative() || offlineTtl.isZero())
                ? DEFAULT_OFFLINE_TTL : offlineTtl;
        this.offlineTtl = off.compareTo(this.staleTtl) < 0 ? this.staleTtl : off;
    }

    /**
     * Demote stale devices. Two DB-side bulk updates; returns the counts for
     * observability + tests. Idempotent: a run with nothing stale is a no-op.
     */
    @Transactional
    @Scheduled(
            fixedDelayString = "${endpoint-admin.device-staleness.reconcile-interval-ms:60000}",
            initialDelayString = "${endpoint-admin.device-staleness.reconcile-initial-delay-ms:60000}"
    )
    public ReconcileResult reconcile() {
        Instant now = Instant.now(clock);
        int markedOffline = deviceRepository.demoteStaleDevices(
                DeviceStatus.STALE, DeviceStatus.OFFLINE, now.minus(offlineTtl));
        int markedStale = deviceRepository.demoteStaleDevices(
                DeviceStatus.ONLINE, DeviceStatus.STALE, now.minus(staleTtl));
        if (markedStale > 0 || markedOffline > 0) {
            log.info("device-staleness reconcile: {} ONLINE->STALE (>{}), {} STALE->OFFLINE (>{})",
                    markedStale, staleTtl, markedOffline, offlineTtl);
        }
        return new ReconcileResult(markedStale, markedOffline);
    }

    /** Reconcile counts for one run (observability + tests). */
    public record ReconcileResult(int markedStale, int markedOffline) {
    }
}
