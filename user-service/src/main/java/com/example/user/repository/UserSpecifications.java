package com.example.user.repository;

import com.example.user.model.User;
import jakarta.persistence.criteria.Expression;
import org.springframework.data.jpa.domain.Specification;

/**
 * Reusable JPA {@link Specification}s for {@link User} query surfaces.
 *
 * <p>Soft-delete (Codex thread {@code 019ea573}, platform-web #770 Phase 2):
 * there is deliberately NO global Hibernate {@code @Where}/{@code @SQLRestriction}
 * on the entity — the identity-resolution security paths must read tombstones
 * explicitly so a deleted user gets a clean {@code 403 USER_DELETED} and is
 * never silently resurrected. Public/query surfaces (list / group / pivot /
 * aggregation) instead exclude tombstones with {@link #notDeleted()}.
 */
public final class UserSpecifications {

    private UserSpecifications() {
    }

    /**
     * Excludes soft-deleted tombstones — {@code deleted_at IS NULL}. Seeded
     * into {@code UserService#buildSpecification} and AND-ed into the SSRM
     * aggregation/pivot criteria so no query surface ever returns a deleted
     * user.
     */
    public static Specification<User> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    /**
     * Can own an assignment (gitops#3834): enabled, not deleted, and bound to a Keycloak subject.
     * An unbound row is excluded because meeting-service stores the assignee as that subject — an
     * unbound user would be offered, fail to resolve, and could never see the task in "Görevlerim".
     */
    public static Specification<User> assignable() {
        return (root, query, cb) -> cb.and(
                cb.isTrue(root.get("enabled")),
                cb.isNull(root.get("deletedAt")),
                cb.isNotNull(root.get("kcSubject")),
                cb.notEqual(cb.trim(root.get("kcSubject")), ""));
    }

    /**
     * Case-insensitive substring match on name or email. The text is matched literally: LIKE
     * wildcards in it are escaped, so {@code "%"} cannot turn a search into a directory dump. Both
     * sides are lower-cased by the database so the comparison uses one set of case rules.
     */
    public static Specification<User> nameOrEmailContains(String text) {
        String pattern = "%" + escapeLike(text) + "%";
        return (root, query, cb) -> {
            Expression<String> lowered = cb.lower(cb.literal(pattern));
            return cb.or(
                    cb.like(cb.lower(root.get("name")), lowered, '\\'),
                    cb.like(cb.lower(root.get("email")), lowered, '\\'));
        };
    }

    /**
     * Directory visibility for a non-admin requester — the same rule as
     * {@code UserService#searchUsers}: global users ({@code company_id IS NULL}) are visible to
     * everyone; a user with a company is visible only inside that company. The requester's own
     * company comes from its directory row, never from a token claim.
     */
    public static Specification<User> visibleToCompany(Long requesterCompanyId) {
        return (root, query, cb) -> requesterCompanyId == null
                ? cb.isNull(root.get("companyId"))
                : cb.or(cb.isNull(root.get("companyId")), cb.equal(root.get("companyId"), requesterCompanyId));
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
