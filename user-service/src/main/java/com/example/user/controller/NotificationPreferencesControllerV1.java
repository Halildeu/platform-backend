package com.example.user.controller;

import com.example.user.dto.v1.NotificationPreferenceDto;
import com.example.user.dto.v1.NotificationPreferencesResponseDto;
import com.example.user.dto.v1.UpdateNotificationPreferencesRequestDto;
import com.example.user.model.User;
import com.example.user.model.UserNotificationPreference;
import com.example.user.service.NotificationPreferencesService;
import com.example.user.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notification-preferences")
public class NotificationPreferencesControllerV1 {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(NotificationPreferencesControllerV1.class);

    private final NotificationPreferencesService notificationPreferencesService;
    private final UserService userService;

    public NotificationPreferencesControllerV1(NotificationPreferencesService notificationPreferencesService, UserService userService) {
        this.notificationPreferencesService = notificationPreferencesService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<NotificationPreferencesResponseDto> getPreferences() {
        User currentUser = requireCurrentUser();
        List<UserNotificationPreference> prefs = notificationPreferencesService.getOrInitForUser(currentUser.getId());
        return ResponseEntity.ok(new NotificationPreferencesResponseDto(toDtoList(prefs)));
    }

    @PatchMapping
    public ResponseEntity<NotificationPreferencesResponseDto> updatePreferences(@RequestBody UpdateNotificationPreferencesRequestDto request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request_body");
        }

        User currentUser = requireCurrentUser();
        List<UserNotificationPreference> updated = notificationPreferencesService.updateForUser(currentUser.getId(), request.getPreferences());
        return ResponseEntity.ok(new NotificationPreferencesResponseDto(toDtoList(updated)));
    }

    private List<NotificationPreferenceDto> toDtoList(List<UserNotificationPreference> prefs) {
        if (prefs == null) return List.of();
        return prefs.stream()
                .map(p -> new NotificationPreferenceDto(p.getChannel(), p.isEnabled(), p.getFrequency(), p.getUpdatedAt()))
                .toList();
    }

    private User requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması gerekli");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof User u) {
            return u;
        }

        String username;
        if (principal instanceof Jwt jwt) {
            username = firstNonBlank(
                    jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("preferred_username"),
                    authentication.getName());
        } else {
            username = authentication.getName();
        }

        if (!StringUtils.hasText(username)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması gerekli");
        }
        return userService.findByEmail(username)
                .orElseThrow(() -> {
                    LOGGER.warn("Keycloak kullanıcısı bulundu ancak yerel profil yok: {}", username);
                    return new ResponseStatusException(HttpStatus.FORBIDDEN, "PROFILE_MISSING");
                });
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}

