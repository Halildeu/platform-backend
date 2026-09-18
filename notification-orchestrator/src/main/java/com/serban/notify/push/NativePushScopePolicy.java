package com.serban.notify.push;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact deployment allowlist; an empty configuration never accepts device tokens. */
@Component
@ConditionalOnProperty(name = "notify.native-push.registry-enabled", havingValue = "true")
public class NativePushScopePolicy {
    private final Set<String> scopes;

    public NativePushScopePolicy(@Value("${notify.native-push.allowed-scopes:}") String configuration) {
        scopes = Arrays.stream(configuration.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (scopes.stream().anyMatch(s -> !s.matches("[A-Za-z0-9_.-]{1,255}/(FCM|APNS)/(TEST|PRODUCTION)")))
            throw new IllegalArgumentException("Invalid native push scope configuration");
    }

    public void requireAllowed(String app, String provider, String environment) {
        if (!scopes.contains(app + "/" + provider + "/" + environment))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "NATIVE_PUSH_SCOPE_NOT_ENABLED");
    }
}
