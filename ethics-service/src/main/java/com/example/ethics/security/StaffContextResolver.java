package com.example.ethics.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.HttpStatus.FORBIDDEN;

@Component
public class StaffContextResolver {
    private final EthicsEntitlementVerifier entitlements;

    public StaffContextResolver(EthicsEntitlementVerifier entitlements) {
        this.entitlements = entitlements;
    }

    public StaffContext required() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Staff identity is required.");
        }
        String org = jwt.getClaimAsString("org_id");
        String subject = jwt.getSubject();
        try {
            if (org == null || subject == null || subject.isBlank()) throw new IllegalArgumentException();
            if (!entitlements.hasManageEntitlement(jwt.getTokenValue())) {
                throw new ResponseStatusException(
                        FORBIDDEN,
                        "Current ETHIC management entitlement is required.");
            }
            return new StaffContext(UUID.fromString(org), subject);
        } catch (ResponseStatusException denied) {
            throw denied;
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(UNAUTHORIZED, "Staff organization context is invalid.");
        }
    }
}
