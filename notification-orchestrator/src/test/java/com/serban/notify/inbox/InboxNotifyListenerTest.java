package com.serban.notify.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * InboxNotifyListener unit test (Faz 23.4 PR-E.4).
 *
 * <p>Test scope:
 * <ul>
 *   <li>Notification handler parses JSON payload correctly + emits Spring event</li>
 *   <li>Wrong channel name → skip silently</li>
 *   <li>Empty payload → log + drop (no event)</li>
 *   <li>Malformed JSON → log + drop (no event)</li>
 *   <li>Missing required fields → log + drop (no event)</li>
 *   <li>cross-pod-enabled=false short-circuits initialize</li>
 * </ul>
 *
 * <p>Live PG connection / LISTEN polling integration test deferred to
 * Testcontainers integration suite (separate test class with real PG).
 * This unit test exercises the {@code handleNotification} private method
 * via reflection for fast feedback on payload parsing logic.
 */
class InboxNotifyListenerTest {

    private DataSource dataSource;
    private ApplicationEventPublisher applicationEventPublisher;
    private ObjectMapper objectMapper;
    private InboxNotifyListener listener;

    @BeforeEach
    void setUp() {
        dataSource = mock(DataSource.class);
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        objectMapper = new ObjectMapper();
        listener = new InboxNotifyListener(dataSource, applicationEventPublisher, objectMapper);
        ReflectionTestUtils.setField(listener, "enabled", true);
    }

    @Test
    void handlerParsesValidPayloadAndEmitsSpringEvent() throws Exception {
        PGNotification notif = mock(PGNotification.class);
        when(notif.getName()).thenReturn(InboxNotifyListener.CHANNEL);
        when(notif.getParameter()).thenReturn(
            "{\"orgId\":\"default\",\"subscriberId\":\"sub-1\",\"unreadCount\":5}"
        );

        invokeHandle(notif);

        verify(applicationEventPublisher).publishEvent(argThat((InboxUpdatedEvent ev) ->
            "default".equals(ev.orgId())
                && "sub-1".equals(ev.subscriberId())
                && ev.unreadCount() == 5L
        ));
    }

    @Test
    void wrongChannelSkipsSilently() throws Exception {
        PGNotification notif = mock(PGNotification.class);
        when(notif.getName()).thenReturn("some_other_channel");
        when(notif.getParameter()).thenReturn("{}");

        invokeHandle(notif);

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void emptyPayloadDrops() throws Exception {
        PGNotification notif = mock(PGNotification.class);
        when(notif.getName()).thenReturn(InboxNotifyListener.CHANNEL);
        when(notif.getParameter()).thenReturn("");

        invokeHandle(notif);

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void nullPayloadDrops() throws Exception {
        PGNotification notif = mock(PGNotification.class);
        when(notif.getName()).thenReturn(InboxNotifyListener.CHANNEL);
        when(notif.getParameter()).thenReturn(null);

        invokeHandle(notif);

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void malformedJsonDropsWithoutThrowing() throws Exception {
        PGNotification notif = mock(PGNotification.class);
        when(notif.getName()).thenReturn(InboxNotifyListener.CHANNEL);
        when(notif.getParameter()).thenReturn("not-json{");

        invokeHandle(notif);

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void missingOrgIdDrops() throws Exception {
        PGNotification notif = mock(PGNotification.class);
        when(notif.getName()).thenReturn(InboxNotifyListener.CHANNEL);
        when(notif.getParameter()).thenReturn(
            "{\"subscriberId\":\"sub-1\",\"unreadCount\":5}"
        );

        invokeHandle(notif);

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void missingSubscriberIdDrops() throws Exception {
        PGNotification notif = mock(PGNotification.class);
        when(notif.getName()).thenReturn(InboxNotifyListener.CHANNEL);
        when(notif.getParameter()).thenReturn(
            "{\"orgId\":\"default\",\"unreadCount\":5}"
        );

        invokeHandle(notif);

        verifyNoInteractions(applicationEventPublisher);
    }

    @Test
    void disabledInitializeSkipsBackgroundThread() {
        ReflectionTestUtils.setField(listener, "enabled", false);

        listener.initialize();

        // No exception, no thread; running flag should remain false
        // (verify by checking no DataSource interaction since openConnection
        // is never called)
        verifyNoInteractions(dataSource);
    }

    private void invokeHandle(PGNotification notif) throws Exception {
        Method m = InboxNotifyListener.class.getDeclaredMethod(
            "handleNotification", PGNotification.class);
        m.setAccessible(true);
        m.invoke(listener, notif);
    }
}
