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
}
