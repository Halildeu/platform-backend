package com.example.variant.authz;

import com.example.commonauth.AuthorizationContext;
import org.springframework.security.oauth2.jwt.Jwt;

public interface VariantAuthorizationService {
    AuthorizationContext buildContext(Jwt jwt);

    default void clearCache() {
        // optional; implemented where cache exists
    }
}
