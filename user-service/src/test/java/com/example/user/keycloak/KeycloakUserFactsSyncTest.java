package com.example.user.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.user.repository.UserRepository;

/**
 * Behaviour pins for the cached Keycloak login name (gitops#3291).
 *
 * <p>The interesting cases are all about what the reconcile does when it is
 * NOT given a clean answer — an empty listing, a subject the realm no longer
 * has, Keycloak being down. Each of those has a wrong response that looks
 * reasonable (blank the cache) and a right one (keep what we know).
 */
class KeycloakUserFactsSyncTest {

    private KeycloakAdminClient keycloak;
    private UserRepository users;
    private KeycloakAdminApiProperties props;
    private KeycloakUserFactsSync sync;

    private static UserRepository.KcUsernameRow row(String subject, String cached) {
        return new UserRepository.KcUsernameRow() {
            @Override public String getKcSubject() { return subject; }
            @Override public String getKcUsername() { return cached; }
        };
    }

    @BeforeEach
    void setUp() {
        keycloak = mock(KeycloakAdminClient.class);
        users = mock(UserRepository.class);
        props = new KeycloakAdminApiProperties();
        props.setUsernameSyncTtlSeconds(60);
        when(keycloak.isEnabled()).thenReturn(true);
        when(keycloak.listLastLogins()).thenReturn(Map.of());
        sync = new KeycloakUserFactsSync(keycloak, users, props);
    }

    @Test
    void writesOnlyTheNamesThatActuallyChanged() {
        when(keycloak.listUsernames()).thenReturn(Map.of(
                "sub-a", "halildeu",
                "sub-b", "halil.kocoglu"));
        when(users.findKcUsernameRows()).thenReturn(List.of(
                row("sub-a", "halildeu@gmail.com"),   // renamed in Keycloak
                row("sub-b", "halil.kocoglu")));      // already in step

        assertThat(sync.reconcile()).isEqualTo(1);

        verify(users).applyKcUsername("sub-a", "halildeu");
        verify(users, never()).applyKcUsername("sub-b", "halil.kocoglu");
    }

    @Test
    void steadyStateIssuesNoWrites() {
        when(keycloak.listUsernames()).thenReturn(Map.of("sub-a", "halildeu"));
        when(users.findKcUsernameRows()).thenReturn(List.of(row("sub-a", "halildeu")));

        assertThat(sync.reconcile()).isZero();
        verify(users, never()).applyKcUsername(anyString(), anyString());
    }

    @Test
    void populatesRowsThatHaveNoCachedNameYet() {
        when(keycloak.listUsernames()).thenReturn(Map.of("sub-a", "ai.enes"));
        when(users.findKcUsernameRows()).thenReturn(List.of(row("sub-a", null)));

        assertThat(sync.reconcile()).isEqualTo(1);
        verify(users).applyKcUsername("sub-a", "ai.enes");
    }

    /**
     * An empty realm listing is far likelier to be a permission or paging fault
     * than a realm with no users. Blanking every cached name on that reading
     * would turn a transient fault into visible data loss.
     */
    @Test
    void emptyRealmListingLeavesTheCacheAlone() {
        when(keycloak.listUsernames()).thenReturn(Map.of());
        when(users.findKcUsernameRows()).thenReturn(List.of(row("sub-a", "halildeu")));

        assertThat(sync.reconcile()).isZero();
        verify(users, never()).applyKcUsername(anyString(), anyString());
    }

    /** A subject the realm no longer has keeps its last known name. */
    @Test
    void subjectMissingFromTheListingIsNotBlanked() {
        when(keycloak.listUsernames()).thenReturn(Map.of("sub-a", "halildeu"));
        when(users.findKcUsernameRows()).thenReturn(List.of(
                row("sub-a", "halildeu"),
                row("sub-gone", "departed.person")));

        assertThat(sync.reconcile()).isZero();
        verify(users, never()).applyKcUsername(anyString(), anyString());
    }

    /** The grid must render when Keycloak is down. */
    @Test
    void refreshIfStaleSwallowsKeycloakFailures() {
        when(keycloak.listUsernames()).thenThrow(new RuntimeException("connection refused"));

        sync.refreshIfStale();

        verify(users, never()).applyKcUsername(anyString(), anyString());
    }

    /** Grid traffic must not become Keycloak traffic. */
    @Test
    void secondCallInsideTheTtlWindowDoesNotHitKeycloakAgain() {
        when(keycloak.listUsernames()).thenReturn(Map.of("sub-a", "halildeu"));
        when(users.findKcUsernameRows()).thenReturn(List.of(row("sub-a", "halildeu")));

        sync.refreshIfStale();
        sync.refreshIfStale();

        verify(keycloak, times(1)).listUsernames();
    }

    /**
     * A failed refresh must not be recorded as a fresh one, or a Keycloak blip
     * would freeze the column for a whole TTL window.
     */
    @Test
    void aFailedRefreshDoesNotStartTheTtlClock() {
        when(keycloak.listUsernames())
                .thenThrow(new RuntimeException("connection refused"))
                .thenReturn(Map.of("sub-a", "halildeu"));
        when(users.findKcUsernameRows()).thenReturn(List.of(row("sub-a", null)));

        sync.refreshIfStale();
        sync.refreshIfStale();

        verify(keycloak, times(2)).listUsernames();
        verify(users).applyKcUsername("sub-a", "halildeu");
    }

    /** With no admin client configured the sync is inert, not noisy. */
    @Test
    void disabledClientMeansNoWorkAtAll() {
        when(keycloak.isEnabled()).thenReturn(false);

        sync.refreshIfStale();

        verify(keycloak, never()).listUsernames();
    }

    // ── last-login (gitops#3297) ──────────────────────────────────────────
    //
    // The platform's own last_login is fed by the legacy password login that
    // Keycloak OIDC replaced, so it had never been written for anyone. These
    // pin the one thing that is easy to get wrong: the value must only ever
    // move forward.

    private static final LocalDateTime T_OLD = LocalDateTime.of(2026, 7, 25, 9, 0);
    private static final LocalDateTime T_NEW = LocalDateTime.of(2026, 8, 1, 15, 6);

    private static Instant at(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant();
    }

    @Test
    void recordsTheLatestLoginKeycloakReports() {
        when(keycloak.listUsernames()).thenReturn(Map.of("sub-a", "halildeu"));
        when(keycloak.listLastLogins()).thenReturn(Map.of("sub-a", at(T_NEW)));
        when(users.findKcUsernameRows()).thenReturn(List.of(row("sub-a", "halildeu")));
        when(users.advanceLastLogin("sub-a", T_NEW)).thenReturn(1);

        assertThat(sync.reconcile()).isEqualTo(1);
        verify(users).advanceLastLogin("sub-a", T_NEW);
    }

    /**
     * A user whose last sign-in has aged out of Keycloak's retention window has
     * no event at all. Erasing the stored timestamp on that reading would make
     * a still-true fact disappear.
     */
    @Test
    void aUserWithNoLoginEventIsLeftAlone() {
        when(keycloak.listUsernames()).thenReturn(Map.of("sub-a", "halildeu"));
        when(keycloak.listLastLogins()).thenReturn(Map.of());
        when(users.findKcUsernameRows()).thenReturn(List.of(row("sub-a", "halildeu")));

        assertThat(sync.reconcile()).isZero();
        verify(users, never()).advanceLastLogin(anyString(), any());
    }

    /**
     * The backward-move guard lives in the query, so the reconcile still issues
     * the write — and must report the zero rows it touched rather than counting
     * a change that did not happen.
     */
    @Test
    void anOlderTimestampChangesNothing() {
        when(keycloak.listUsernames()).thenReturn(Map.of("sub-a", "halildeu"));
        when(keycloak.listLastLogins()).thenReturn(Map.of("sub-a", at(T_OLD)));
        when(users.findKcUsernameRows()).thenReturn(List.of(row("sub-a", "halildeu")));
        when(users.advanceLastLogin("sub-a", T_OLD)).thenReturn(0);   // guard refused it

        assertThat(sync.reconcile()).isZero();
    }

    /** A rename and a fresh login in the same round are both applied. */
    @Test
    void nameAndLoginAdvanceTogether() {
        when(keycloak.listUsernames()).thenReturn(Map.of("sub-a", "halildeu"));
        when(keycloak.listLastLogins()).thenReturn(Map.of("sub-a", at(T_NEW)));
        when(users.findKcUsernameRows()).thenReturn(List.of(row("sub-a", "halildeu@gmail.com")));
        when(users.advanceLastLogin("sub-a", T_NEW)).thenReturn(1);

        assertThat(sync.reconcile()).isEqualTo(2);
        verify(users).applyKcUsername("sub-a", "halildeu");
        verify(users).advanceLastLogin("sub-a", T_NEW);
    }

    /** An event for someone this panel does not know is simply not ours. */
    @Test
    void loginsForUnknownSubjectsAreIgnored() {
        when(keycloak.listUsernames()).thenReturn(Map.of("sub-a", "halildeu"));
        when(keycloak.listLastLogins()).thenReturn(Map.of("sub-stranger", at(T_NEW)));
        when(users.findKcUsernameRows()).thenReturn(List.of(row("sub-a", "halildeu")));

        assertThat(sync.reconcile()).isZero();
        verify(users, never()).advanceLastLogin(anyString(), any());
    }
}
