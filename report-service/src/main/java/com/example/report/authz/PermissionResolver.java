package com.example.report.authz;

import org.springframework.security.oauth2.jwt.Jwt;

public interface PermissionResolver {
    AuthzMeResponse getAuthzMe(Jwt jwt);
}
