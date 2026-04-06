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
            String permissionKey
    ) {}

    public static ExplainResponseDto allowed(String permissionType, String permissionKey,
                                             String roleName, String grantType,
                                             List<String> userRoles, Map<String, List<Long>> scopes) {
        return new ExplainResponseDto(true, "ALLOWED",
                new ExplainDetails(roleName, grantType, permissionType, permissionKey),
                userRoles, scopes);
    }

    public static ExplainResponseDto denied(String reason, String permissionType, String permissionKey,
                                            String roleName, String grantType,
                                            List<String> userRoles, Map<String, List<Long>> scopes) {
        return new ExplainResponseDto(false, reason,
                new ExplainDetails(roleName, grantType, permissionType, permissionKey),
                userRoles, scopes);
    }
}
