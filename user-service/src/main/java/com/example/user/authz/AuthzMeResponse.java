package com.example.user.authz;

import java.util.List;
import java.util.Set;

public record AuthzMeResponse(
        String userId,
        Set<String> permissions,
        List<ScopeSummaryDto> allowedScopes,
        boolean superAdmin) {
}
