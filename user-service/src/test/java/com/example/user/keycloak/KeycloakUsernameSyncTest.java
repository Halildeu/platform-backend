package com.example.user.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
class KeycloakUsernameSyncTest {

    private KeycloakAdminClient keycloak;
    private UserRepository users;
    private KeycloakAdminApiProperties props;
    private KeycloakUsernameSync sync;

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
        sync = new KeycloakUsernameSync(keycloak, users, props);
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
}
