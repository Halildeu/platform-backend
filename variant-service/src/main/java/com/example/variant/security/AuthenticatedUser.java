package com.example.variant.security;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record AuthenticatedUser(Long id,
                                String email,
                                String role,
                                List<String> permissions,
                                List<String> allowedGridIds) {

    public AuthenticatedUser {
        permissions = permissions == null ? Collections.emptyList() : List.copyOf(permissions);
        allowedGridIds = allowedGridIds == null ? Collections.emptyList() : List.copyOf(allowedGridIds);
    }

    public boolean isAdmin() {
        if (role == null) {
            return false;
        }
        String normalized = role.startsWith("ROLE_") ? role.substring(5) : role;
        return Objects.equals(normalized, "ADMIN");
    }

    public boolean hasPermission(String permission) {
        if (permission == null || permissions == null || permissions.isEmpty()) {
            return false;
        }
        return permissions.stream()
                .filter(Objects::nonNull)
                .anyMatch(p -> p.equalsIgnoreCase(permission));
    }
}
