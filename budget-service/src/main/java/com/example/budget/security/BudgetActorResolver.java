package com.example.budget.security;

import java.util.Collection;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class BudgetActorResolver {

    public BudgetActor resolve(Authentication authentication, long requestedCompanyId) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated JWT is required");
        }

        String tenantId = firstNonBlank(claimText(jwt, "tenant_id"), claimText(jwt, "org_id"));
        String subject = jwt.getSubject();
        if (tenantId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant and subject claims are required");
        }
        if (!containsCompany(jwt, requestedCompanyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Company is outside the token scope");
        }
        return new BudgetActor(tenantId, requestedCompanyId, subject);
    }

    private boolean containsCompany(Jwt jwt, long companyId) {
        Object many = jwt.getClaim("company_ids");
        if (many instanceof Collection<?> values) {
            return values.stream().anyMatch(value -> companyId == parseCompany(value));
        }
        Object one = jwt.getClaim("company_id");
        return one != null && companyId == parseCompany(one);
    }

    private long parseCompany(Object raw) {
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private String claimText(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
