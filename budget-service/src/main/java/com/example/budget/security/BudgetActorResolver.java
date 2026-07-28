package com.example.budget.security;

import com.example.budget.security.BudgetAuthorizationClient.AuthorizationSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class BudgetActorResolver {
    private final BudgetAuthorizationClient authorizationClient;

    public BudgetActorResolver(BudgetAuthorizationClient authorizationClient) {
        this.authorizationClient = authorizationClient;
    }

    public BudgetActor resolve(Authentication authentication, long requestedCompanyId) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated JWT is required");
        }

        String tenantId = firstNonBlank(claimText(jwt, "tenant_id"), claimText(jwt, "org_id"));
        String subject = jwt.getSubject();
        if (tenantId == null || subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant and subject claims are required");
        }
        AuthorizationSnapshot authorization = authorizationClient.fetch(jwt.getTokenValue());
        if (!authorization.superAdmin()
                && !authorization.allowedCompanyIds().contains(requestedCompanyId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Company is outside the authoritative scope");
        }
        return new BudgetActor(
                tenantId,
                requestedCompanyId,
                authorization.userId(),
                authorization.allowedProjectIds(),
                authorization.superAdmin());
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
