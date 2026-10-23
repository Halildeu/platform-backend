package com.serban.notify.push;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class NativePushScopePolicyTest {
    @Test void emptyConfigurationDeniesRegistration() {
        assertThrows(ResponseStatusException.class, () ->
            new NativePushScopePolicy("").requireAllowed("com.example.test", "FCM", "TEST"));
    }
    @Test void applicationProviderAndEnvironmentMustAllMatch() {
        var policy = new NativePushScopePolicy("com.example.test/FCM/TEST, com.example.ios/APNS/TEST");
        assertDoesNotThrow(() -> policy.requireAllowed("com.example.test", "FCM", "TEST"));
        assertDoesNotThrow(() -> policy.requireAllowed("com.example.ios", "APNS", "TEST"));
        assertThrows(ResponseStatusException.class, () -> policy.requireAllowed("com.example.test", "FCM", "PRODUCTION"));
        assertThrows(ResponseStatusException.class, () -> policy.requireAllowed("com.example.test", "APNS", "TEST"));
        assertThrows(ResponseStatusException.class, () -> policy.requireAllowed("com.other", "FCM", "TEST"));
    }
    @Test void invalidConfigurationFailsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new NativePushScopePolicy("*/FCM/TEST"));
    }
}
