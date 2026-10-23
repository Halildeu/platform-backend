package com.serban.notify.api;

import com.serban.notify.push.NativePushRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notify/native-push/registrations")
@ConditionalOnProperty(name = "notify.native-push.registry-enabled", havingValue = "true")
public class NativePushRegistrationController {
    private final NativePushRegistry registry;
    private final NotifyOrgAccessGuard orgGuard;
    private final SubscriberIdentityGuard subscriberGuard;

    public NativePushRegistrationController(NativePushRegistry registry, NotifyOrgAccessGuard orgGuard,
                                             SubscriberIdentityGuard subscriberGuard) {
        this.registry = registry;
        this.orgGuard = orgGuard;
        this.subscriberGuard = subscriberGuard;
    }

    @PostMapping
    public Map<String, Object> register(@AuthenticationPrincipal Jwt jwt,
        @RequestHeader("X-Org-Id") String org, @RequestHeader("X-Subscriber-Id") String subscriber,
        @Valid @RequestBody Registration request) {
        authorize(jwt, org, subscriber);
        UUID id = registry.register(org, subscriber, request.installationId(), request.applicationId(),
            request.provider(), request.environment(), request.token());
        return Map.of("registrationId", id, "status", "registered");
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt, @RequestHeader("X-Org-Id") String org,
        @RequestHeader("X-Subscriber-Id") String subscriber, @PathVariable UUID id) {
        authorize(jwt, org, subscriber);
        registry.remove(org, subscriber, id);
    }

    private void authorize(Jwt jwt, String org, String subscriber) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (org == null || org.isBlank() || org.length() > 64 || subscriber == null || subscriber.isBlank() || subscriber.length() > 128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        orgGuard.requireOrgAccessOrThrow(org);
        subscriberGuard.requireMatchOrThrow(subscriber);
    }

    @DeleteMapping("/installations/{installation}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeInstallation(@AuthenticationPrincipal Jwt jwt, @RequestHeader("X-Org-Id") String org,
        @RequestHeader("X-Subscriber-Id") String subscriber, @PathVariable UUID installation,
        @Valid @RequestBody Removal request) {
        authorize(jwt, org, subscriber);
        registry.removeInstallation(org, subscriber, installation, request.applicationId(), request.provider(), request.environment());
    }

    public record Removal(@NotNull @Pattern(regexp = "[A-Za-z0-9_.-]{1,255}") String applicationId,
        @NotNull @Pattern(regexp = "FCM|APNS") String provider,
        @NotNull @Pattern(regexp = "TEST|PRODUCTION") String environment) {}

    public record Registration(@NotNull UUID installationId,
        @NotNull @Pattern(regexp = "[A-Za-z0-9_.-]{1,255}") String applicationId,
        @NotNull @Pattern(regexp = "FCM|APNS") String provider,
        @NotNull @Pattern(regexp = "TEST|PRODUCTION") String environment,
        @NotNull @Pattern(regexp = "[A-Za-z0-9_:.\\-]{1,4096}") String token) {
        @Override public String toString() { return "NativePushRegistration[redacted]"; }
    }
}
