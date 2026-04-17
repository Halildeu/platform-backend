package com.example.permission.dto.v1;

import java.util.List;
import java.util.Map;

public record ExplainResponseDto(
        boolean allowed,
        String reason,
        ExplainDetails details,
        List<String> userRoles,
        Map<String, List<Long>> userScopes
) {
    public record ExplainDetails(
            String roleName,
            String grantType,
            String permissionType,
            String permissionKey,
            String scopeType,
            Long scopeRefId
    ) {}

    public static ExplainResponseDto allowed(String permissionType, String permissionKey,
                                             String roleName, String grantType,
                                             List<String> userRoles, Map<String, List<Long>> scopes) {
        return new ExplainResponseDto(true, "ALLOWED",
                new ExplainDetails(roleName, grantType, permissionType, permissionKey, null, null),
                userRoles, scopes);
    }

    public static ExplainResponseDto denied(String reason, String permissionType, String permissionKey,
                                            String roleName, String grantType,
                                            List<String> userRoles, Map<String, List<Long>> scopes) {
        return new ExplainResponseDto(false, reason,
                new ExplainDetails(roleName, grantType, permissionType, permissionKey, null, null),
                userRoles, scopes);
    }

    /**
     * NO_SCOPE deny — preserves the original permissionType/permissionKey under
     * ExplainDetails and carries the requested scopeType/scopeRefId in dedicated
     * fields so the UI can render both "permission X was requested" and
     * "scope {type}:{refId} is not accessible" without overloading the
     * permissionType/permissionKey slots (P1.9 regression guard).
     */
    public static ExplainResponseDto deniedNoScope(String permissionType, String permissionKey,
                                                    String scopeType, Long scopeRefId,
                                                    List<String> userRoles,
                                                    Map<String, List<Long>> scopes) {
        return new ExplainResponseDto(false, "NO_SCOPE",
                new ExplainDetails(null, null, permissionType, permissionKey, scopeType, scopeRefId),
                userRoles, scopes);
    }
}
