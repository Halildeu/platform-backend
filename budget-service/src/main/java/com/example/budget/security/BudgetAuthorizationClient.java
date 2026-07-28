package com.example.budget.security;

import java.util.Set;

public interface BudgetAuthorizationClient {

    AuthorizationSnapshot fetch(String bearerToken);

    record AuthorizationSnapshot(
            String userId,
            Set<Long> allowedCompanyIds,
            Set<Long> allowedProjectIds,
            boolean superAdmin) {

        public AuthorizationSnapshot {
            allowedCompanyIds =
                    allowedCompanyIds == null ? Set.of() : Set.copyOf(allowedCompanyIds);
            allowedProjectIds =
                    allowedProjectIds == null ? Set.of() : Set.copyOf(allowedProjectIds);
        }
    }
}
