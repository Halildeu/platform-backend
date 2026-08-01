package com.example.user.keycloak;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.user.repository.UserRepository;

/**
 * Keeps {@code users.kc_username} in step with Keycloak (gitops#3291).
 *
 * <p>Keycloak owns the login name; this is a cache, and it is treated like one:
 *
 * <ul>
 *   <li><b>Fail-open.</b> A refresh failure is logged and swallowed. The users
 *       grid must render when Keycloak is unreachable — a stale or missing
 *       login name is a worse column, not a broken page.</li>
 *   <li><b>TTL-guarded.</b> The grid asks for a refresh on every page fetch;
 *       at most one Keycloak round trip actually happens per TTL window,
 *       so grid traffic does not translate into Keycloak traffic.</li>
 *   <li><b>Single-flight.</b> Concurrent grid requests do not stack up
 *       refreshes; losers return immediately and read whatever is committed.</li>
 *   <li><b>Write-on-difference.</b> In the steady state a reconcile issues zero
 *       writes, so the TTL can stay short without churning the table.</li>
 * </ul>
 *
 * <p>There is deliberately no {@code @Scheduled} here: user-service has no
 * scheduler, and switching {@code @EnableScheduling} on for the whole
 * application to keep one cache warm would arm every future {@code @Scheduled}
 * annotation in the service as a side effect. Refresh is demand-driven, which
 * is also the only time anyone can observe the value.
 */
@Component
public class KeycloakUsernameSync {

    private static final Logger log = LoggerFactory.getLogger(KeycloakUsernameSync.class);

    private final KeycloakAdminClient keycloak;
    private final UserRepository users;
    private final KeycloakAdminApiProperties props;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile Instant lastSuccess = Instant.EPOCH;

    public KeycloakUsernameSync(KeycloakAdminClient keycloak,
                                UserRepository users,
                                KeycloakAdminApiProperties props) {
        this.keycloak = keycloak;
        this.users = users;
        this.props = props;
    }

    /**
     * Refresh if the cache is older than the TTL. Never throws — callers are on
     * a read path that must survive Keycloak being down.
     */
    public void refreshIfStale() {
        if (!keycloak.isEnabled()) {
            return;
        }
        Duration ttl = Duration.ofSeconds(Math.max(0, props.getUsernameSyncTtlSeconds()));
        if (Instant.now().isBefore(lastSuccess.plus(ttl))) {
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            int changed = reconcile();
            lastSuccess = Instant.now();
            if (changed > 0) {
                log.info("kc-username sync: {} row(s) updated", changed);
            }
        } catch (RuntimeException ex) {
            // Fail-open: the grid still renders, with whatever was last known.
            log.warn("kc-username sync failed, serving cached login names: {}", ex.toString());
        } finally {
            inFlight.set(false);
        }
    }

    /**
     * Not transactional, on purpose. The Keycloak listing is a network round
     * trip; wrapping this method would pin a pooled database connection for its
     * whole duration. Each write carries its own short transaction
     * ({@code UserRepository.applyKcUsername}), and a partial reconcile is
     * harmless — the next window finishes it.
     */
    int reconcile() {
        Map<String, String> live = keycloak.listUsernames();
        if (live.isEmpty()) {
            // An empty realm listing is far more likely to be a permission or
            // paging fault than a realm with no users. Blanking every cached
            // name on that reading would turn a transient fault into visible
            // data loss, so treat it as "no information" instead.
            log.warn("kc-username sync: realm listing returned no users, leaving cache untouched");
            return 0;
        }
        List<UserRepository.KcUsernameRow> rows = users.findKcUsernameRows();
        int changed = 0;
        for (UserRepository.KcUsernameRow row : rows) {
            String fresh = live.get(row.getKcSubject());
            if (fresh == null || fresh.equals(row.getKcUsername())) {
                // Absent from the listing: the row points at a subject this
                // realm no longer has. Keep the last known name — it is the
                // only remaining clue to what the account was.
                continue;
            }
            users.applyKcUsername(row.getKcSubject(), fresh);
            changed++;
        }
        return changed;
    }
}
