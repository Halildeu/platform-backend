package com.example.user.dto.v1;

import com.example.user.model.User;

public final class UserDtoMapper {

    private UserDtoMapper() {
    }

    public static UserSummaryDto toSummary(User user) {
        if (user == null) {
            return null;
        }
        UserSummaryDto dto = new UserSummaryDto(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.isEnabled(),
                user.getCreateDate(),
                user.getLastLogin()
        );
        dto.setSessionTimeoutMinutes(user.getSessionTimeoutMinutes());
        return dto;
    }

    public static UserDetailDto toDetail(User user) {
        if (user == null) {
            return null;
        }
        // Codex 019e1bed REVISE-1 restored after Session 47 hotfix chain:
        // kcSubject removed from the public V1 detail surface; auth-service
        // impersonation target resolution uses the internal service-token
        // endpoint /api/users/internal/{id}/impersonation-target.
        return new UserDetailDto(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.isEnabled(),
                user.getCreateDate(),
                user.getLastLogin(),
                user.getSessionTimeoutMinutes(),
                user.getLocale()
        );
    }
}
