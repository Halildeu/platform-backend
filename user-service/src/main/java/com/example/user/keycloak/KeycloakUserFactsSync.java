package com.example.user.keycloak;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.user.repository.UserRepository;

/**
 * Keeps the Keycloak-owned facts on a user row in step with Keycloak:
 * {@code kc_username} (gitops#3291) and {@code last_login} (gitops#3297).
 *
 * <p>Both moved to Keycloak when authentication did, and the panel's copy of
 * each is a cache, treated like one:
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
 *
 * <p><b>Last-login is monotonic.</b> Keycloak keeps login events for
 * {@code eventsExpiration} (7 days here), so an account that last signed in
 * before the window simply has no event. Writing whatever Keycloak reports
 * would then drag a good stored timestamp backwards — or to nothing — and the
 * panel would show a login that appears to have moved into the past. The write
 * advances only, enforced in SQL rather than here, so two concurrent reconciles
 * cannot race it either.
 *
 * <p>That is also why persisting matters rather than reading through: once a
 * timestamp is stored it outlives the event that produced it. The retention
 * window bounds how far back the first sync can reach, not what accumulates
 * from then on.
 */
@Component
public class KeycloakUserFactsSync {

    private static final Logger log = LoggerFactory.getLogger(KeycloakUserFactsSync.class);

    private final KeycloakAdminClient keycloak;
    private final UserRepository users;
    private final KeycloakAdminApiProperties props;

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private volatile Instant lastSuccess = Instant.EPOCH;

    public KeycloakUserFactsSync(KeycloakAdminClient keycloak,
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
                log.info("kc-facts sync: {} row(s) updated", changed);
            }
        } catch (RuntimeException ex) {
            // Fail-open: the grid still renders, with whatever was last known.
            log.warn("kc-facts sync failed, serving cached values: {}", ex.toString());
        } finally {
            inFlight.set(false);
        }
    }

    /**
     * Not transactional, on purpose. The Keycloak calls are network round trips;
     * wrapping this method would pin a pooled database connection for their
     * whole duration. Each write carries its own short transaction, and a
     * partial reconcile is harmless — the next window finishes it.
     */
    int reconcile() {
        Map<String, String> live = keycloak.listUsernames();
        if (live.isEmpty()) {
            // An empty realm listing is far more likely to be a permission or
            // paging fault than a realm with no users. Blanking every cached
            // name on that reading would turn a transient fault into visible
            // data loss, so treat it as "no information" instead.
            log.warn("kc-facts sync: realm listing returned no users, leaving cache untouched");
            return 0;
        }
        // Absent or failing is "no information": an empty map simply advances
        // nothing, because the write is monotonic.
        Map<String, Instant> logins = keycloak.listLastLogins();

        List<UserRepository.KcUsernameRow> rows = users.findKcUsernameRows();
        int changed = 0;
        for (UserRepository.KcUsernameRow row : rows) {
            String subject = row.getKcSubject();

            String fresh = live.get(subject);
            if (fresh != null && !fresh.equals(row.getKcUsername())) {
                users.applyKcUsername(subject, fresh);
                changed++;
            }
            // A subject missing from the listing keeps its last known name — it
            // is the only remaining clue to what the account was.

            Instant login = logins.get(subject);
            if (login != null) {
                // Advance-only, and the guard lives in the query: Keycloak
                // forgets events past its retention, so a plain write would drag
                // a good stored timestamp backwards for anyone whose last sign-in
                // has aged out.
                changed += users.advanceLastLogin(
                        subject, LocalDateTime.ofInstant(login, ZoneId.systemDefault()));
            }
        }
        return changed;
    }
}
