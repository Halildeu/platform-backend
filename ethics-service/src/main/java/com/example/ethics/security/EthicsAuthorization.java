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
        if (!properties.isEnabled()) return false;
        try {
            // The first deployable slice grants staff at the org-owned product
            // object. Database queries still constrain every case by org_id.
            // Per-case conflict and recusal tuples are a later, explicit gate.
            return openFga.check(staff.subject(), relation, PRODUCT_OBJECT, staff.orgId().toString());
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
