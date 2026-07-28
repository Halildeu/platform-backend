package com.example.ethics.security;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Object authorization boundary. A deny and an unavailable policy engine are indistinguishable. */
@Component
public class EthicsAuthorization {
    public static final String PRODUCT_OBJECT = "ethics_product";
    public static final String CASE_OBJECT = "ethics_case";
    private final OpenFgaAuthzService openFga;
    private final OpenFgaProperties properties;

    public EthicsAuthorization(OpenFgaAuthzService openFga, OpenFgaProperties properties) {
        this.openFga = openFga;
        this.properties = properties;
    }

    /**
     * The same decision as {@link #can}, resolved once for a whole list.
     *
     * <p>{@code can} asks the policy engine three questions per case: product membership,
     * then the two negative case relations. Product membership does not vary between the
     * cases of one request, so a list of 138 asked it 138 times and made 414 round trips
     * to answer 6 KB — the cost grew with the caseload, not with the answer. This asks
     * three questions for the whole list.
     *
     * <p>The economy must not become a hole. A negative relation read as an empty list is
     * the dangerous direction: "nobody is recused" is what an outage looks like, and it
     * grants exactly the cases recusal exists to withhold. So the blocked sets are read
     * through {@link OpenFgaAuthzService#listObjectsResult}, and if either read could not
     * be made the gate denies every case rather than guessing that nothing was blocked.
     */
    public CaseGate gateFor(StaffContext staff, String relation) {
        if (!properties.isEnabled()) return CaseGate.DENY_ALL;
        try {
            if (!canOnProduct(staff, relation)) return CaseGate.DENY_ALL;
            var conflicted = openFga.listObjectsResult(staff.subject(), "conflicted", CASE_OBJECT);
            if (!conflicted.available()) return CaseGate.DENY_ALL;
            var recused = openFga.listObjectsResult(staff.subject(), "recused", CASE_OBJECT);
            if (!recused.available()) return CaseGate.DENY_ALL;
            var blocked = new java.util.HashSet<String>(conflicted.objectIds());
            blocked.addAll(recused.objectIds());
            return new CaseGate(true, java.util.Set.copyOf(blocked));
        } catch (RuntimeException unavailable) {
            return CaseGate.DENY_ALL;
        }
    }

    /**
     * Which cases of an already tenant-scoped list this staff member may see.
     *
     * @param productMember whether the product relation held at all; false denies everything
     * @param blockedCaseIds cases withheld by conflict or recusal
     */
    public record CaseGate(boolean productMember, java.util.Set<String> blockedCaseIds) {
        static final CaseGate DENY_ALL = new CaseGate(false, java.util.Set.of());

        public boolean allows(UUID caseId) {
            return productMember && caseId != null && !blockedCaseIds.contains(caseId.toString());
        }
    }

    public boolean can(StaffContext staff, String relation, UUID caseId) {
        if (!properties.isEnabled() || caseId == null) return false;
        try {
            // The first slice authorizes product membership while database
            // predicates bind the case to the staff tenant. Conflict and recusal
            // are negative case relations. Their result must preserve the
            // difference between "relation absent" and OpenFGA unavailable;
            // checkNoCacheResult provides that third state without consulting the
            // generic TTL cache.
            var product = openFga.checkNoCacheResult(
                    staff.subject(), relation, PRODUCT_OBJECT, staff.orgId().toString());
            if (!product.allowed()) return false;

            var conflicted = openFga.checkNoCacheResult(
                    staff.subject(), "conflicted", CASE_OBJECT, caseId.toString());
            if (!isHealthyAbsence(conflicted)) return false;

            var recused = openFga.checkNoCacheResult(
                    staff.subject(), "recused", CASE_OBJECT, caseId.toString());
            return isHealthyAbsence(recused);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private boolean isHealthyAbsence(OpenFgaAuthzService.CheckResult result) {
        return !result.allowed() && "no_relation".equals(result.reason());
    }

    public void require(StaffContext staff, String relation, UUID caseId) {
        if (!can(staff, relation, caseId)) {
            // Do not disclose object existence, conflict state, or policy-engine health.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found.");
        }
    }

    /**
     * Is this subject a member of that org's product? (ES-203, B+ slice 1.)
     *
     * <p>Asked about <em>someone else</em>, so it must not fall back to allow. An unreadable policy
     * engine answers "not a member": refusing a legitimate assignment is recoverable, while
     * recording a participant who was never verified puts an unchecked name into the data that
     * routing and conflict decisions read.
     *
     * <p>Deliberately checks {@code case_viewer} rather than a raw role — that derived relation is
     * already "may see this org's cases minus content_denied", which is exactly the bar for being
     * assignable. A {@code technical_admin} is therefore not assignable, which is the intent.
     */
    /**
     * A permission that belongs to the product rather than to one case.
     *
     * <p>{@link #can} requires a case id and denies without one, correctly: every question
     * it answers is about a particular report. "May this person assign staff at all" is not
     * such a question, and squeezing it through a case would make the answer depend on a
     * case the caller has not named.
     */
    public boolean canOnProduct(StaffContext staff, String relation) {
        if (!properties.isEnabled() || staff == null || staff.orgId() == null) return false;
        try {
            return openFga.checkNoCacheResult(
                    staff.subject(), relation, PRODUCT_OBJECT, staff.orgId().toString()).allowed();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * ES-203 / ES-206 — who in this org may be put on a case.
     *
     * <p>Answers from the product, not from any case: the question is "who works ethics
     * here", and a case-scoped list would say which staff are already attached to which
     * report. In a whistleblowing system that is correlation data, and an endpoint that
     * hands it out has no business existing to fill a dropdown.
     *
     * <p>An unreachable policy engine is reported as unavailable rather than as an empty
     * list. The two are opposite facts — "nobody may be assigned" and "we could not find
     * out" — and a caller that cannot tell them apart will show the first while the
     * second is true.
     */
    public OpenFgaAuthzService.UserListResult assignableStaff(UUID orgId) {
        if (!properties.isEnabled() || orgId == null) {
            return OpenFgaAuthzService.UserListResult.unavailable("authz-disabled");
        }
        return openFga.listUsers("case_viewer", PRODUCT_OBJECT, orgId.toString());
    }

    public boolean isProductMember(String subject, UUID orgId) {
        if (!properties.isEnabled() || subject == null || orgId == null) return false;
        try {
            return openFga.checkNoCacheResult(subject, "case_viewer", PRODUCT_OBJECT, orgId.toString())
                    .allowed();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Records that the acting staff member has stepped away from a case (ES-203).
     *
     * <p>The subject is always the caller's own token — there is no parameter for <em>who</em>. A
     * recusal able to name someone else would be a way to remove a colleague from a case they are
     * handling, which is exactly the capability an interested party would want. Keeping the actor
     * implicit makes that unrepresentable rather than merely forbidden.
     *
     * <p>Callers reach this only after {@code require(.., "case_viewer", ..)} passed, which already
     * fails when the relation is present — so "already recused" is a concurrent-write race, not a
     * repeat declaration. The check is kept for that race and the call is a no-op when it fires; it
     * is not a second outcome the caller should report differently, and treating it as one produced
     * an audit event no request could ever reach.
     */
    public void recuseSelf(StaffContext staff, UUID caseId) {
        OpenFgaAuthzService.CheckResult existing =
                openFga.checkNoCacheResult(staff.subject(), "recused", CASE_OBJECT, caseId.toString());
        if (existing.allowed()) {
            return;
        }
        if (!isHealthyAbsence(existing)) {
            // The policy engine did not answer "no such relation" — it did not answer at all.
            // Writing an audit entry that says "recused" without having established it would put a
            // false statement in an append-only ledger, so refuse instead of guessing.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Case not found.");
        }
        openFga.writeTuple(staff.subject(), "recused", CASE_OBJECT, caseId.toString());
    }
}
