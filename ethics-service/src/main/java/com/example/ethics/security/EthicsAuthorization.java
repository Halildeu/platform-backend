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
     * Records that the acting staff member has stepped away from a case (ES-203).
     *
     * <p>The subject is always the caller's own token — there is no parameter for <em>who</em>. A
     * recusal able to name someone else would be a way to remove a colleague from a case they are
     * handling, which is exactly the capability an interested party would want. Keeping the actor
     * implicit makes that unrepresentable rather than merely forbidden.
     *
     * @return true when this call created the relation, false when it was already present
     */
    public boolean recuseSelf(StaffContext staff, UUID caseId) {
        OpenFgaAuthzService.CheckResult existing =
                openFga.checkNoCacheResult(staff.subject(), "recused", CASE_OBJECT, caseId.toString());
        if (existing.allowed()) {
            return false;
        }
        if (!isHealthyAbsence(existing)) {
            // The policy engine did not answer "no such relation" — it did not answer at all.
            // Writing an audit entry that says "recused" without having established it would put a
            // false statement in an append-only ledger, so refuse instead of guessing.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Case not found.");
        }
        openFga.writeTuple(staff.subject(), "recused", CASE_OBJECT, caseId.toString());
        return true;
    }
}
