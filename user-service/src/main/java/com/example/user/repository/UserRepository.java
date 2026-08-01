package com.example.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.example.user.model.User;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    /**
     * Check if a user exists by email
     * 
     * @param email The email to check
     * @return true if the email exists, false otherwise
     */
    boolean existsByEmail(String email);

    /**
     * Find a user by email
     *
     * @param email The email to search for
     * @return Optional<User> if the user exists
     */
    Optional<User> findByEmail(String email);

    /**
     * Find a user by Keycloak subject (the {@code sub} claim UUID).
     *
     * <p>Primary lookup key for the Keycloak lazy-provision bridge
     * ({@link com.example.user.security.KeycloakUserAutoProvisionFilter}):
     * an M365 auto-provisioned identity is matched by {@code kc_subject}
     * first so a later email change in Keycloak does not orphan the
     * platform profile.
     *
     * @param kcSubject the Keycloak subject UUID
     * @return Optional<User> if a row with that subject exists
     */
    Optional<User> findByKcSubject(String kcSubject);

    /**
     * Faz 35 ES-203/C — batch display-name resolution for the ethics picker.
     *
     * <p>Soft-deleted rows are excluded on purpose: an erased person's name
     * must not resurface through a directory lookup, and a caller receives
     * the same silence for "deleted" as for "never existed" — the endpoint
     * must not become an existence oracle for erased identities.
     */
    @Query("select u from User u where u.kcSubject in :subjects and u.deletedAt is null")
    List<User> findActiveByKcSubjectIn(@Param("subjects") Collection<String> subjects);

    /**
     * Find a user by case-insensitive email match.
     *
     * <p>Used by the lazy-provision bridge as the secondary lookup
     * (after {@link #findByKcSubject}) so a token whose email casing
     * differs from the stored canonical lowercase value still resolves
     * to the existing row instead of attempting a duplicate insert.
     *
     * @param email the email to match, any casing
     * @return Optional<User> if a row with that email (any casing) exists
     */
    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    // ── Soft-delete tombstone-aware reads (Codex 019ea573, #770 Phase 2) ──
    // No global @Where on the entity (identity paths read tombstones
    // explicitly); these active-only variants back the read/query surfaces
    // that must never return a deleted user.

    /**
     * Find an <em>active</em> (non-tombstoned) user by id.
     *
     * <p>Backs {@code getUser}, {@code updateUser} and {@code updateActivation}
     * so those surfaces return {@code 404} for a soft-deleted row instead of
     * acting on a tombstone. The explicit {@code restore} path uses the raw
     * {@link #findById(Object)} so it can still locate the tombstone.
     */
    @Query("select u from User u where u.id = :id and u.deletedAt is null")
    Optional<User> findActiveById(@Param("id") Long id);

    /**
     * Find an <em>active</em> (non-tombstoned) user by exact email. Backs the
     * Spring Security {@code loadUserByUsername} local-login path so a
     * soft-deleted account cannot authenticate.
     */
    @Query("select u from User u where u.email = :email and u.deletedAt is null")
    Optional<User> findActiveByEmail(@Param("email") String email);

    /**
     * Find an <em>active</em> (non-tombstoned) user by case-insensitive email.
     * Backs the {@code GET /by-email} read surface.
     */
    @Query("select u from User u where lower(u.email) = lower(:email) and u.deletedAt is null")
    Optional<User> findActiveByEmailIgnoreCase(@Param("email") String email);

    @Modifying(clearAutomatically = true)
    @Query("update User u set u.sessionTimeoutMinutes = :timeout where u.sessionTimeoutMinutes is null or u.sessionTimeoutMinutes < 1")
    int normalizeSessionTimeouts(@Param("timeout") int timeout);

    /** Projection for the login-name reconcile — subject + cached name only. */
    interface KcUsernameRow {
        String getKcSubject();
        String getKcUsername();
    }

    /**
     * Every active row that is bound to a Keycloak user, with its cached login
     * name. Read as a projection so the reconcile can diff without loading
     * entities it has no intention of mutating (gitops#3291).
     */
    @Query("select u.kcSubject as kcSubject, u.kcUsername as kcUsername from User u "
            + "where u.kcSubject is not null and u.deletedAt is null")
    List<KcUsernameRow> findKcUsernameRows();

    /**
     * Write one cached login name.
     * <p>
     * A targeted JPQL update rather than a loaded-entity save: {@code kc_username}
     * is a cache of a Keycloak fact, so it must not bump {@code @Version} and
     * make an unrelated concurrent edit of that user fail an optimistic-lock
     * check it should never have been part of.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true)
    @Query("update User u set u.kcUsername = :username where u.kcSubject = :subject")
    int applyKcUsername(@Param("subject") String subject, @Param("username") String username);

    /**
     * Move a cached last-login forward, never backward (gitops#3297).
     * <p>
     * The {@code lastLogin < :at} guard is the whole point and it belongs in the
     * statement, not in the caller: Keycloak only keeps login events for its
     * retention window, so a user whose last sign-in has aged out yields no
     * event, and an unguarded write would erase a timestamp that is still true.
     * Putting the comparison in SQL also makes two concurrent reconciles safe
     * without a lock — the older one simply updates nothing.
     * <p>
     * Like {@code applyKcUsername} this deliberately does not bump
     * {@code @Version}: it caches a Keycloak fact and must not make an unrelated
     * concurrent edit of that user fail an optimistic-lock check.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(clearAutomatically = true)
    @Query("update User u set u.lastLogin = :at "
            + "where u.kcSubject = :subject and (u.lastLogin is null or u.lastLogin < :at)")
    int advanceLastLogin(@Param("subject") String subject,
                         @Param("at") java.time.LocalDateTime at);
}
