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
    private final OpenFgaAuthzService openFga;
    private final OpenFgaProperties properties;

    public EthicsAuthorization(OpenFgaAuthzService openFga, OpenFgaProperties properties) {
        this.openFga = openFga;
        this.properties = properties;
    }

    public boolean can(StaffContext staff, String relation, UUID caseId) {
        if (!properties.isEnabled() || caseId == null) return false;
        try {
            // Product entitlement and the DB org predicate are necessary but
            // not sufficient: a conflicted/recused actor must disappear at the
            // object boundary without receiving a case-existence signal.
            if (!openFga.check(staff.subject(), relation, PRODUCT_OBJECT, staff.orgId().toString())) {
                return false;
            }
            boolean conflicted = openFga.check(staff.subject(), "conflicted", "ethics_case", caseId.toString());
            boolean recused = openFga.check(staff.subject(), "recused", "ethics_case", caseId.toString());
            return !conflicted && !recused;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public void require(StaffContext staff, String relation, UUID caseId) {
        if (!can(staff, relation, caseId)) {
            // Do not disclose object existence, conflict state, or policy-engine health.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found.");
        }
    }
}
