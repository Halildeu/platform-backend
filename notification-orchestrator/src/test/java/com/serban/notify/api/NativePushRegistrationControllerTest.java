package com.serban.notify.api;

import com.serban.notify.push.NativePushRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativePushRegistrationControllerTest {
    private final NativePushRegistry registry = mock(NativePushRegistry.class);
    private final NotifyOrgAccessGuard org = mock(NotifyOrgAccessGuard.class);
    private final SubscriberIdentityGuard subscriber = mock(SubscriberIdentityGuard.class);
    private final NativePushRegistrationController controller = new NativePushRegistrationController(registry, org, subscriber);
    private final Jwt jwt = Jwt.withTokenValue("synthetic").header("alg", "RS256").subject("alice").build();
    private final UUID installation = UUID.randomUUID();
    private final NativePushRegistrationController.Registration request =
        new NativePushRegistrationController.Registration(installation, "com.workcube.meeting", "FCM", "TEST", "synthetic-token");

    @Test void unauthenticatedCannotRegisterOrDelete() {
        assertThrows(ResponseStatusException.class, () -> controller.register(null, "org", "alice", request));
        assertThrows(ResponseStatusException.class, () -> controller.remove(null, "org", "alice", UUID.randomUUID()));
        verifyNoInteractions(registry);
    }

    @Test void crossOrgIsDeniedBeforeStorage() {
        doThrow(new AccessDeniedException("denied")).when(org).requireOrgAccessOrThrow("other");
        assertThrows(AccessDeniedException.class, () -> controller.register(jwt, "other", "alice", request));
        verifyNoInteractions(registry);
    }

    @Test void anotherSubscriberCannotDelete() {
        doThrow(new AccessDeniedException("denied")).when(subscriber).requireMatchOrThrow("bob");
        assertThrows(AccessDeniedException.class, () -> controller.remove(jwt, "org", "bob", UUID.randomUUID()));
        verifyNoInteractions(registry);
    }

    @Test void returnsOnlyRegistrationMetadata() {
        UUID id = UUID.randomUUID();
        when(registry.register("org", "alice", installation, "com.workcube.meeting", "FCM", "TEST", "synthetic-token")).thenReturn(id);
        var result = controller.register(jwt, "org", "alice", request);
        assertEquals(id, result.get("registrationId"));
        assertEquals(2, result.size());
        assertFalse(request.toString().contains("synthetic-token"));
        verify(org).requireOrgAccessOrThrow("org");
        verify(subscriber).requireMatchOrThrow("alice");
    }
}
